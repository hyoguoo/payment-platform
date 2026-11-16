package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;
import java.time.Instant;

/**
 * PgInboxChannel 의 큐 항목 — inboxId 와 offer 시점의 OTel Context + MDC snapshot 을 동봉한다.
 *
 * <p>offer 시점(Kafka consumer thread — smoke trace 활성)에서 context 를 캡처해 두면
 * take 시점(worker VT thread — application start context = 빈 context) 이후 처리 직전에
 * restore 할 수 있으므로 traceparent 가 그대로 전파된다.
 *
 * <p>{@code enqueuedAt}: 큐에 넣은 시각. 워커 수가 동시 처리 상한이라 큐에서 오래 기다릴 수 있다. 정체 기준보다
 * 오래 기다린 작업은 그사이 폴링 워커가 같은 행을 회수했을 수 있어, 워커가 처리하지 않고 폴링에 맡긴다.
 */
public record InboxJob(
        Long inboxId,
        Context otelContext,
        ContextSnapshot snapshot,
        Instant enqueuedAt
) implements ImmediateJob {

}
