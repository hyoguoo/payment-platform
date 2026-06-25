package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 종결 가드 재발행 카운터.
 *
 * <p>{@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase#handle}
 * 의 종결 가드 분기에서, {@code status==DONE && message==APPROVED}(= 재배달 신호)로
 * 재고 확정을 재발행할 때 호출된다.
 * 라벨: {@code status} 1개만 — orderId/userId 등 고카디널리티 라벨 금지(불변식).
 *
 * <p>재발행 분기는 dedupe(payment_event_dedupe)를 거치지 않으므로, 브로커 커밋이
 * 반복 실패하면 동일 재배달이 매번 재발행을 트리거할 수 있다. 이 카운터로 빈도를
 * 가시화해 비정상 반복(예: 커밋 실패 루프)을 운영에서 조기 감지한다.
 *
 * <p>{@link #record} 는 throw-free 계약을 유지한다.
 * Micrometer Counter.increment() 자체는 안전하나, null status 입력에 대해 명시적으로
 * noop 처리한다. 가드 분기에서 예외 전파 시 RuntimeException → 재시도 5회 → DLQ 경로로
 * 변환되는 것을 방지한다.
 *
 * <p>Eager 등록: 생성자에서 재발행 트리거 상태인 DONE 1종 라벨 카운터를 0으로
 * 사전 등록한다 — 기동 직후 Prometheus 스크레이프에 "No data" 없이 0 시리즈가 노출됨.
 */
@Component
public class PaymentConfirmTerminalResendMetrics {

    private static final String METRIC_NAME = "payment_confirm_terminal_resend_total";

    private final MeterRegistry meterRegistry;
    private final Map<PaymentEventStatus, Counter> resendCounters = new ConcurrentHashMap<>();

    public PaymentConfirmTerminalResendMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // Eager 등록: 재발행 트리거 상태인 DONE 1종을 생성자에서 0으로 사전 등록
        resendCounters.put(PaymentEventStatus.DONE, buildCounter(PaymentEventStatus.DONE));
    }

    /**
     * 종결 가드 재발행 카운터를 증가시킨다.
     *
     * <p>throw-free 계약: null status 는 noop 으로 처리하며 예외를 던지지 않는다.
     *
     * @param status 재발행이 트리거된 시점의 {@link PaymentEventStatus} — 라벨 {@code status} 값
     */
    public void record(PaymentEventStatus status) {
        if (status == null) {
            return;
        }
        resendCounters.computeIfAbsent(status, this::buildCounter).increment();
    }

    private Counter buildCounter(PaymentEventStatus status) {
        return Counter.builder(METRIC_NAME)
                .description("Total number of stock-committed resends triggered by terminal guard redelivery")
                .tag("status", status.name())
                .register(meterRegistry);
    }
}
