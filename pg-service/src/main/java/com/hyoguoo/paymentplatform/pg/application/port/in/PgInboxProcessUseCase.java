package com.hyoguoo.paymentplatform.pg.application.port.in;

/**
 * pg-service inbound 포트 — inbox 처리 워커 진입점.
 *
 * <p>워커(infrastructure) → application 방향 의존을 hexagonal 규칙에 따라 이 포트로 역전한다.
 *
 * <p>사용처:
 * <ul>
 *   <li>{@code PgInboxImmediateWorker} (PCS-12) — AFTER_COMMIT channel take 후 {@link #processPending} 호출</li>
 *   <li>{@code PgInboxPollingWorker} (PCS-13) — IN_PROGRESS 좀비 회수 시 {@link #processInProgressZombie} 호출</li>
 * </ul>
 *
 * <p>구현체 {@code PgInboxProcessor} 는 application/service 계층에 위치 (PCS-8).
 */
public interface PgInboxProcessUseCase {

    /**
     * PENDING 상태의 inbox row 를 처리한다.
     *
     * <p>워커 TX_A: SKIP LOCKED SELECT → PENDING→IN_PROGRESS 전환 후 벤더 호출 + 결과 반영.
     *
     * @param inboxId pg_inbox.id
     */
    void processPending(Long inboxId);

    /**
     * IN_PROGRESS 좀비 상태의 inbox row 를 재처리한다.
     *
     * <p>폴링 워커가 일정 시간 IN_PROGRESS 로 머문 row 를 감지하여 호출.
     * 새 root span 을 생성하여 복구 트레이스를 기존 트레이스와 분리한다.
     *
     * @param inboxId pg_inbox.id
     */
    void processInProgressZombie(Long inboxId);
}
