package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Context;

/**
 * Immediate 워커가 채널에서 꺼내 처리하는 작업 단위 공통 인터페이스.
 *
 * <p>{@link InboxJob} / {@link OutboxJob} 이 구현한다.
 * {@link com.hyoguoo.paymentplatform.pg.infrastructure.scheduler.AbstractImmediateWorker} 가
 * 이 인터페이스를 통해 OTel Context + MDC snapshot 을 꺼내 이중 scope 복원을 수행한다.
 */
public interface ImmediateJob {

    /**
     * offer 시점에 캡처된 OTel Context.
     */
    Context otelContext();

    /**
     * offer 시점에 캡처된 Micrometer ContextSnapshot (MDC 포함).
     */
    ContextSnapshot snapshot();
}
