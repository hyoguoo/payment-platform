package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>QuarantineCompensationHandler와 경계:
 * - FCG 진입점: QUARANTINED 전이만 수행, Redis INCR 즉시 금지 → Reconciler 위임.
 * - DLQ_CONSUMER 진입점: TX 커밋 후 즉시 Redis INCR 시도 + 실패 시 QuarantineCompensationScheduler 재시도.
 * - Reconciler: QUARANTINED 레코드에 대해 rollback() 호출 (멱등성은 Redis INCR 자체가 보장).
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
        // TODO: 구현 예정 (RED 단계)
        throw new UnsupportedOperationException("PaymentReconciler.scan() 미구현");
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
