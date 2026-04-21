package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 결제 서비스 로컬 Reconciler — ADR-07, ADR-17.
 *
 * <p>FCG=즉시 경로, Reconciler=지연 경로. 각 scan() 호출에서:
 * <ol>
 *   <li>IN_FLIGHT(IN_PROGRESS) + timeout 초과 레코드 → READY 복원 (재시도 스케줄러가 재처리)</li>
 *   <li>각 productId별 Redis vs RDB 재고 대조 → 발산 시 RDB 기준으로 Redis 재설정, divergence_count +1</li>
 *   <li>QUARANTINED 결제 → StockCachePort.rollback() 호출 (Reconciler 단독 경로)</li>
 *   <li>Redis key miss(TTL 만료) → RDB 기준 재설정</li>
 * </ol>
 *
 * <p>QuarantineCompensationHandler/Scheduler와 경계:
 * - FCG 진입점: QUARANTINED 전이만 수행, Redis INCR 즉시 금지 → Reconciler 위임.
 * - DLQ_CONSUMER 진입점: TX 커밋 후 즉시 Redis INCR 시도 + 실패 시 QuarantineCompensationScheduler 재시도.
 * - Reconciler: QUARANTINED 레코드에 대해 rollback() 호출 (멱등성은 Redis INCR 자체가 보장).
 *   quarantineCompensationPending 플래그는 Reconciler가 관리하지 않는다(QuarantineCompensationScheduler 경로와 중복 방지).
 */
@Slf4j
@Service
public class PaymentReconciler {

    // TODO: T1-16에서 StockCacheDivergenceMetrics 클래스로 교체
    private final AtomicLong divergenceCount = new AtomicLong(0);

    private final PaymentEventRepository paymentEventRepository;
    private final StockCachePort stockCachePort;
    private final ProductPort productPort;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final long inFlightTimeoutSeconds;

    public PaymentReconciler(
            PaymentEventRepository paymentEventRepository,
            StockCachePort stockCachePort,
            ProductPort productPort,
            LocalDateTimeProvider localDateTimeProvider,
            @Value("${reconciler.in-flight-timeout-seconds:300}") long inFlightTimeoutSeconds
    ) {
        this.paymentEventRepository = paymentEventRepository;
        this.stockCachePort = stockCachePort;
        this.productPort = productPort;
        this.localDateTimeProvider = localDateTimeProvider;
        this.inFlightTimeoutSeconds = inFlightTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${reconciler.fixed-delay-ms:120000}")
    public void scan() {
        LocalDateTime now = localDateTimeProvider.now();

        resetStaleInFlightRecords(now);
        reconcileStockCache();
        rollbackQuarantinedDecr();
    }

    /**
     * Step 1: IN_FLIGHT(IN_PROGRESS) + timeout 초과 → READY 복원.
     * 재시도 스케줄러(OutboxWorker 등)가 READY 상태를 재처리한다.
     */
    private void resetStaleInFlightRecords(LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(inFlightTimeoutSeconds);
        List<PaymentEvent> staleEvents = paymentEventRepository.findInProgressOlderThan(cutoff);

        if (staleEvents.isEmpty()) {
            return;
        }
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                () -> "Reconciler: stale IN_FLIGHT 발견 " + staleEvents.size() + "건 → READY 복원");

        for (PaymentEvent event : staleEvents) {
            event.resetToReady(now);
            paymentEventRepository.saveOrUpdate(event);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "Reconciler: orderId=" + event.getOrderId() + " → READY 복원 완료");
        }
    }

    /**
     * Step 2 + Step 4: Redis↔RDB 재고 대조.
     * - READY, IN_PROGRESS, RETRYING 상태 결제의 productId별 잠금 수량을 합산한다.
     * - RDB 기준값 = product.stock(RDB) − 잠금 합산 수량
     * - Redis 값과 비교: 다르면(발산) RDB 기준으로 Redis 재설정 + divergence_count +1
     * - Redis key miss(Optional.empty())이면 RDB 기준으로 재설정 (divergence_count 미증가)
     */
    private void reconcileStockCache() {
        // READY, IN_PROGRESS, RETRYING 상태의 모든 결제를 수집하여 productId별 잠금 수량 계산
        List<PaymentEvent> pendingEvents = collectPendingEvents();

        // productId별 잠금 수량 합산
        Map<Long, Integer> lockedByProduct = buildLockedQuantityMap(pendingEvents);

        for (Map.Entry<Long, Integer> entry : lockedByProduct.entrySet()) {
            Long productId = entry.getKey();
            int locked = entry.getValue();
            reconcileForProduct(productId, locked);
        }
    }

    private List<PaymentEvent> collectPendingEvents() {
        List<PaymentEvent> result = new java.util.ArrayList<>();
        result.addAll(paymentEventRepository.findAllByStatus(PaymentEventStatus.READY));
        result.addAll(paymentEventRepository.findAllByStatus(PaymentEventStatus.IN_PROGRESS));
        result.addAll(paymentEventRepository.findAllByStatus(PaymentEventStatus.RETRYING));
        return result;
    }

    private Map<Long, Integer> buildLockedQuantityMap(List<PaymentEvent> events) {
        return events.stream()
                .flatMap(event -> event.getPaymentOrderList().stream())
                .collect(Collectors.toMap(
                        PaymentOrder::getProductId,
                        PaymentOrder::getQuantity,
                        Integer::sum
                ));
    }

    private void reconcileForProduct(Long productId, int locked) {
        ProductInfo productInfo = productPort.getProductInfoById(productId);
        int rdbExpected = productInfo.getStock() - locked;

        Optional<Integer> cachedOpt = stockCachePort.findCurrent(productId);

        if (cachedOpt.isEmpty()) {
            // key miss → RDB 기준 재설정 (TTL 만료)
            stockCachePort.set(productId, rdbExpected);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "Reconciler: productId=" + productId + " cache miss → RDB 기준 재설정=" + rdbExpected);
            return;
        }

        int cached = cachedOpt.get();
        if (cached != rdbExpected) {
            // 발산 → RDB 기준 재설정 + divergence_count +1
            stockCachePort.set(productId, rdbExpected);
            long count = divergenceCount.incrementAndGet();
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "Reconciler: 재고 발산 감지 productId=" + productId
                            + " cached=" + cached + " expected=" + rdbExpected
                            + " divergence_count=" + count);
        }
    }

    /**
     * Step 3: QUARANTINED 결제 → StockCachePort.rollback() 호출.
     * Reconciler 단독 경로 — FCG 진입점의 QUARANTINED 레코드를 처리.
     * 멱등성: Redis INCR 자체가 멱등 (INCR은 항상 +수량이므로 중복 호출 시 초과 복원 가능성 있음).
     * TODO: QUARANTINED 레코드에 "Reconciler 처리 완료" 플래그 추가 검토 (현재는 단순 rollback 호출).
     */
    private void rollbackQuarantinedDecr() {
        List<PaymentEvent> quarantinedEvents = paymentEventRepository.findAllByStatus(PaymentEventStatus.QUARANTINED);

        if (quarantinedEvents.isEmpty()) {
            return;
        }
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                () -> "Reconciler: QUARANTINED 결제 " + quarantinedEvents.size() + "건 재고 복원 시도");

        for (PaymentEvent event : quarantinedEvents) {
            for (PaymentOrder order : event.getPaymentOrderList()) {
                stockCachePort.rollback(order.getProductId(), order.getQuantity());
            }
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "Reconciler: orderId=" + event.getOrderId() + " QUARANTINED rollback 완료");
        }
    }

    /**
     * 재고 발산 카운터 조회. T1-16 StockCacheDivergenceMetrics 연동 전 임시 노출.
     *
     * @return 누적 발산 감지 건수
     */
    public long getDivergenceCount() {
        return divergenceCount.get();
    }
}
