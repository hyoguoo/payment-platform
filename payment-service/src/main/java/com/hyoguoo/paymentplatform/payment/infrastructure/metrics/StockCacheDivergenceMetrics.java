package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCacheDivergenceRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 재고 캐시 발산 감지 counter (ADR-20, ADR-31).
 *
 * <p>메트릭명: {@code payment.stock_cache.divergence_count}
 *
 * <p>K9e: {@link StockCacheDivergenceRecorder} 포트 구현체.
 * {@link com.hyoguoo.paymentplatform.payment.application.service.PaymentReconciler}가
 * 포트 인터페이스만 의존하여 infrastructure 직접 참조를 차단한다.
 */
@Slf4j
@Component
public class StockCacheDivergenceMetrics implements StockCacheDivergenceRecorder {

    public static final String METRIC_NAME = "payment.stock_cache.divergence_count";

    private final Counter counter;

    public StockCacheDivergenceMetrics(MeterRegistry meterRegistry) {
        this.counter = Counter.builder(METRIC_NAME)
                .description("재고 캐시(Redis)와 RDB 간 발산 감지 누적 건수")
                .register(meterRegistry);
    }

    /**
     * 발산이 감지된 경우 호출한다. counter를 1 증가시킨다.
     */
    public void increment() {
        counter.increment();
    }
}
