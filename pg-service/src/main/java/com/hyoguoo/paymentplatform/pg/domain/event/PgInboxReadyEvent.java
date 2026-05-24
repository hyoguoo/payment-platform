package com.hyoguoo.paymentplatform.pg.domain.event;

/**
 * pg_inbox row 가 DB 에 PENDING 상태로 삽입되고 트랜잭션이 커밋된 직후,
 * AFTER_COMMIT 채널 적재 리스너(PgInboxReadyEventHandler) 가 수신하는 도메인 이벤트.
 *
 * <p>{@link PgOutboxReadyEvent} 와 대칭 위치 — 필드 구성을 1:1 로 맞춘다.
 * 필드는 inboxId 하나 — pg 쪽 partition key 는 외부 payload 속성이므로 id 기반이 안전하다.
 *
 * <p>발행 지점:
 * <pre>
 *   applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inbox.getId()));
 * </pre>
 *
 * <p>수신 지점:
 * <pre>
 *   PgInboxReadyEventHandler — AFTER_COMMIT 트랜잭션 단계에서 channel 에 적재
 * </pre>
 */
public record PgInboxReadyEvent(Long inboxId) {

    public Long getInboxId() {
        return inboxId;
    }
}
