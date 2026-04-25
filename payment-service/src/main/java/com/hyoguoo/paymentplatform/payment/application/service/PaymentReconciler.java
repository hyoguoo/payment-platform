package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCacheDivergenceRecorder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 결제 서비스 로컬 Reconciler — ADR-07, ADR-17.
 *
 * <p>각 scan() 호출에서:
 * <ol>
 *   <li>IN_FLIGHT(IN_PROGRESS) + timeout 초과 레코드 → READY 복원 (재시도 스케줄러가 재처리)</li>
 *   <li>각 productId별 Redis vs RDB 재고 대조 → 발산 시 RDB 기준으로 Redis 재설정, divergence_count +1</li>
 *   <li>Redis key miss(TTL 만료) → RDB 기준 재설정</li>
 * </ol>
 *
 * <p>QUARANTINED 결제는 홀딩 상태이므로 재고 복구 대상이 아니다 (ADR-15).
 * 재고 복구는 FAIL 경로에서만 발생(FailureCompensationService → stock.events.restore).
 */
@Slf4j
@Service
public class PaymentReconciler {

    private final PaymentEventRepository paymentEventRepository;
    private final StockCachePort stockCachePort;
    private final ProductPort productPort;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final StockCacheDivergenceRecorder divergenceRecorder;
    private final long inFlightTimeoutSeconds;

    public PaymentReconciler(
            PaymentEventRepository paymentEventRepository,
            StockCachePort stockCachePort,
            ProductPort productPort,
            LocalDateTimeProvider localDateTimeProvider,
            StockCacheDivergenceRecorder divergenceRecorder,
            @Value("${reconciler.in-flight-timeout-seconds:300}") long inFlightTimeoutSeconds
    ) {
        this.paymentEventRepository = paymentEventRepository;
        this.stockCachePort = stockCachePort;
        this.productPort = productPort;
        this.localDateTimeProvider = localDateTimeProvider;
        this.divergenceRecorder = divergenceRecorder;
        this.inFlightTimeoutSeconds = inFlightTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${reconciler.fixed-delay-ms:120000}")
    public void scan() {
        LocalDateTime now = localDateTimeProvider.now();

        resetStaleInFlightRecords(now);
        reconcileStockCache();
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
            divergenceRecorder.increment();
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "Reconciler: 재고 발산 감지 productId=" + productId
                            + " cached=" + cached + " expected=" + rdbExpected);
        }
    }

}
