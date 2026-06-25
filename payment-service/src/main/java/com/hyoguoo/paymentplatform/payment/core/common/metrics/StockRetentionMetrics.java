package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 선차감 재고 미복구 카운터.
 *
 * <p>{@link com.hyoguoo.paymentplatform.payment.application.OutboxAsyncConfirmService}
 * 에서 확정 TX({@code executeConfirmTx})가 실패했을 때 호출된다. 선차감된 재고와 토큰은
 * 보상(increment)하지 않고 차감 상태를 그대로 유지한다 — 동시 confirm·재확정 과매도를
 * 0으로 만들기 위한 의도적 설계이며, 이 카운터는 그 침묵 손실을 가시화하는 용도다.
 *
 * <p>라벨 없음(단일 카운터) — 본 메트릭은 발생 빈도 추적이 목적이라 고카디널리티 라벨을
 * 두지 않는다(고카디널리티 라벨 금지와 동일한 취지).
 *
 * <p>{@link #record} 는 throw-free 계약을 유지한다. Micrometer Counter.increment() 자체는
 * 안전하다.
 *
 * <p>Eager 등록: 생성자에서 카운터를 0으로 사전 등록한다 — 기동 직후 Prometheus 스크레이프에
 * "No data" 없이 0 시리즈가 노출됨.
 */
@Component
public class StockRetentionMetrics {

    private static final String METRIC_NAME = "stock_retention_unrecovered_total";

    private final Counter unrecoveredCounter;

    public StockRetentionMetrics(MeterRegistry meterRegistry) {
        this.unrecoveredCounter = Counter.builder(METRIC_NAME)
                .description("Total number of unrecovered stock decrements after confirm tx failure")
                .register(meterRegistry);
    }

    /**
     * 미복구 선차감 카운터를 증가시킨다.
     */
    public void record() {
        unrecoveredCounter.increment();
    }
}
