package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 재고 캐시 발산(Redis↔RDB divergence) 감지 counter 포트.
 * ADR-20, ADR-31.
 *
 * <p>K9e: application 계층이 infrastructure.metrics를 직접 참조하지 않도록 port 추출.
 * 구현체: {@code payment.infrastructure.metrics.StockCacheDivergenceMetrics}.
 * {@link com.hyoguoo.paymentplatform.payment.application.service.PaymentReconciler}가
 * Redis↔RDB 발산을 감지할 때 {@link #increment()}를 호출한다.
 */
public interface StockCacheDivergenceRecorder {

    /**
     * 발산이 감지된 경우 호출한다. counter를 1 증가시킨다.
     */
    void increment();
}
