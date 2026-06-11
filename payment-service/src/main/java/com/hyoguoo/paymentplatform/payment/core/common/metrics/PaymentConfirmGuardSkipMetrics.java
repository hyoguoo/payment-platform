package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <p>Eager 등록: 생성자에서 {@code canApplyConfirmResult()==false} 인 6종
 * (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) 라벨 카운터를
 * 0 으로 사전 등록한다 — 기동 직후 Prometheus 스크레이프에 "No data" 없이 0 시리즈가 노출됨.
 */
@Component
public class PaymentConfirmGuardSkipMetrics {

    private static final String METRIC_NAME = "payment_confirm_guard_skip_total";

    private final MeterRegistry meterRegistry;
    private final Map<PaymentEventStatus, Counter> skipCounters = new ConcurrentHashMap<>();

    public PaymentConfirmGuardSkipMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Eager 등록: canApplyConfirmResult()==false 인 6종을 생성자에서 0으로 사전 등록
        Arrays.stream(PaymentEventStatus.values())
                .filter(s -> !s.canApplyConfirmResult())
                .forEach(s -> skipCounters.put(s, buildCounter(s)));
    }

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
        skipCounters.computeIfAbsent(status, this::buildCounter).increment();
    }

    private Counter buildCounter(PaymentEventStatus status) {
        return Counter.builder(METRIC_NAME)
                .description("Total number of confirm guard skips by terminal status")
                .tag("status", status.name())
                .register(meterRegistry);
    }
}
