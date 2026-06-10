package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 종결 상태 가드 스킵 카운터 — D13.
 *
 * <p>{@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase#handle}
 * 의 {@code canApplyConfirmResult()==false} noop 분기에서 호출된다.
 * 라벨: {@code status} 1개만 — orderId/userId 등 고카디널리티 라벨 금지(D7 불변식).
 *
 * <p>{@link #record} 는 throw-free 계약을 유지한다.
 * Micrometer Counter.increment() 자체는 안전하나, null status 입력에 대해 명시적으로 noop 처리한다.
 * 가드 noop 분기에서 예외 전파 시 RuntimeException → 재시도 5회 → DLQ 경로로 변환되는 것을 방지한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentConfirmGuardSkipMetrics {

    private static final String METRIC_NAME = "payment_confirm_guard_skip_total";

    private final MeterRegistry meterRegistry;

    private final Map<PaymentEventStatus, Counter> skipCounters = new ConcurrentHashMap<>();

    /**
     * 가드 스킵 카운터를 증가시킨다.
     *
     * <p>throw-free 계약: null status 는 noop 으로 처리하며 예외를 던지지 않는다.
     *
     * @param status 스킵된 시점의 {@link PaymentEventStatus} — 라벨 {@code status} 값
     */
    public void record(PaymentEventStatus status) {
        if (status == null) {
            return;
        }
        Counter counter = skipCounters.computeIfAbsent(status, s ->
                Counter.builder(METRIC_NAME)
                        .description("Total number of confirm guard skips by terminal status")
                        .tag("status", s.name())
                        .register(meterRegistry)
        );
        counter.increment();
    }
}
