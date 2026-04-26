package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;

/**
 * PgOutboxChannel 의 큐 항목 — outboxId 와 offer 시점의 OTel Context + MDC snapshot 을 동봉한다.
 *
 * <p>offer 시점(Kafka consumer thread — smoke trace 활성)에서 context 를 캡처해 두면
 * take 시점(worker VT thread — application start context = 빈 context) 이후 relay 직전에
 * restore 할 수 있으므로 payment.events.confirmed Kafka 헤더에 smoke traceparent 가 그대로 전파된다.
 */
public record OutboxJob(
        Long outboxId,
        Context otelContext,
        ContextSnapshot snapshot
) {

}
