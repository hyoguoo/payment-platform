package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;

/**
 * PgInboxChannel 의 큐 항목 — inboxId 와 offer 시점의 OTel Context + MDC snapshot 을 동봉한다.
 *
 * <p>offer 시점(Kafka consumer thread — smoke trace 활성)에서 context 를 캡처해 두면
 * take 시점(worker VT thread — application start context = 빈 context) 이후 처리 직전에
 * restore 할 수 있으므로 traceparent 가 그대로 전파된다.
 *
 * <p>{@link OutboxJob} 1:1 거울 — 필드 시그니처 동일.
 */
public record InboxJob(
        Long inboxId,
        Context otelContext,
        ContextSnapshot snapshot
) {

}
