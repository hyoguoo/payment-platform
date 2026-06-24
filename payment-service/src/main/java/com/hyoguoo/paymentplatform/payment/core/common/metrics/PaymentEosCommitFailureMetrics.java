package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * payment-service EOS 커밋 반복 실패 격리(DLQ) 도달 카운터.
 *
 * <p>{@code KafkaConsumerConfig} 의 {@code afterRollbackProcessor} 가 backoff 소진 후
 * DLQ recoverer 를 발화시킬 때(= {@code events.confirmed} 메시지를
 * {@code events.confirmed.dlq} 로 발행) 호출된다.
 *
 * <p>라벨 없음(단일 카운터) — 발생 빈도 추적이 목적이라 고카디널리티 라벨을 두지 않는다
 * ({@code PgDlqReachMetrics} 동형).
 *
 * <p>{@link #record} 는 throw-free 계약을 유지한다. Micrometer Counter.increment() 자체는
 * 안전하다. recoverer 발화 경로에서 예외가 전파되면 DLQ 발행 자체가 막힐 수 있으므로
 * 이 계약이 중요하다.
 *
 * <p>Eager 등록: 생성자에서 카운터를 0으로 사전 등록한다 — 기동 직후 Prometheus 스크레이프에
 * "No data" 없이 0 시리즈가 노출됨.
 */
@Component
public class PaymentEosCommitFailureMetrics {

    private static final String METRIC_NAME = "payment_eos_commit_failure_dlq_total";

    private final Counter dlqReachedCounter;

    public PaymentEosCommitFailureMetrics(MeterRegistry meterRegistry) {
        this.dlqReachedCounter = Counter.builder(METRIC_NAME)
                .description("Total number of EOS commitTransaction() failures reaching DLQ after backoff exhaustion")
                .register(meterRegistry);
    }

    /**
     * EOS 커밋 실패 DLQ 도달 카운터를 증가시킨다.
     */
    public void record() {
        dlqReachedCounter.increment();
    }
}
