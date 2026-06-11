package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 이벤트 발행/종결 흐름 카운터 — 비즈니스 대시보드 funnel 지원.
 *
 * <p>등록 메트릭:
 * <ul>
 *   <li>{@code payment.event.published} — Prometheus 노출명 {@code payment_event_published_total}.
 *       결제 이벤트가 최초 생성(READY 진입)될 때마다 1 증가.</li>
 *   <li>{@code payment.event.terminal} — Prometheus 노출명 {@code payment_event_terminal_total}.
 *       결제 상태가 종결(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED)로 전이될 때마다 1 증가.</li>
 * </ul>
 *
 * <p>라벨 없음 — D7 불변식 준수(orderId/userId 등 고카디널리티 라벨 금지).
 * in-flight = {@code published_total - terminal_total} 식이 대시보드에서 미완결 이벤트를 추적한다.
 *
 * <p>두 카운터는 생성자에서 eager 등록 — hot path 에서 등록 예외 가능성을 제거한다.
 * {@link #recordPublished()} 와 {@link #recordTerminal()} 은 never-throw 계약을 유지한다.
 */
@Slf4j
@Component
public class PaymentEventFlowMetrics {

    private static final String METRIC_PUBLISHED = "payment.event.published";
    private static final String METRIC_TERMINAL = "payment.event.terminal";

    private final Counter publishedCounter;
    private final Counter terminalCounter;

    public PaymentEventFlowMetrics(MeterRegistry meterRegistry) {
        this.publishedCounter = Counter.builder(METRIC_PUBLISHED)
                .description("Total number of payment events published (created with READY status)")
                .register(meterRegistry);
        this.terminalCounter = Counter.builder(METRIC_TERMINAL)
                .description("Total number of payment events reaching terminal status (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED)")
                .register(meterRegistry);
    }

    /**
     * 결제 이벤트 발행(READY 생성) 시 호출. never-throw.
     */
    public void recordPublished() {
        publishedCounter.increment();
    }

    /**
     * 결제 이벤트가 종결 상태(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED)로 전이될 때 호출. never-throw.
     * QUARANTINED 는 복구 대기 상태로 종결에 포함하지 않는다.
     */
    public void recordTerminal() {
        terminalCounter.increment();
    }
}
