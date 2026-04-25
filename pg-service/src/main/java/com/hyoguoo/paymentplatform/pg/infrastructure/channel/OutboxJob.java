package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;

/**
 * PgOutboxChannel 의 큐 항목 — outboxId 와 offer 시점의 OTel Context + MDC snapshot 을 동봉한다.
 *
 * <p>T-J4: offer 시점(Kafka consumer thread — smoke trace 활성)에서 context 를 캡처하여
 * take 시점(worker VT thread — application start context = 빈 context) 이후 relay 직전에
 * restore 함으로써 payment.events.confirmed Kafka 헤더에 smoke traceparent 가 정확히 전파된다.
 *
 * <p>ADR-13: QUARANTINED 홀딩 — 재시도 없음.
 * ADR-15: pg-service FCG — PgFinalConfirmationGate 단일 진입점.
 */
public record OutboxJob(
        Long outboxId,
        Context otelContext,
        ContextSnapshot snapshot
) {

}
