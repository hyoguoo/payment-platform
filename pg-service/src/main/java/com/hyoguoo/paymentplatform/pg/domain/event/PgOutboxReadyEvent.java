package com.hyoguoo.paymentplatform.pg.domain.event;

/**
 * pg_outbox row 가 DB 에 삽입되고 트랜잭션이 커밋된 직후,
 * OutboxReadyEventHandler 가 수신하는 도메인 이벤트.
 *
 * <p>ADR-04 대칭: payment-service 의 PaymentConfirmEvent 와 대등한 위치.
 * 필드는 outboxId 하나 — pg 쪽 partition key 는 외부 payload 속성이므로 id 기반이 안전.
 *
 * <p>발행 지점(후속 T2a-06 PgConfirmCommandHandler):
 * <pre>
 *   applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(outbox.getId()));
 * </pre>
 */
public record PgOutboxReadyEvent(Long outboxId) {

    public Long getOutboxId() {
        return outboxId;
    }
}
