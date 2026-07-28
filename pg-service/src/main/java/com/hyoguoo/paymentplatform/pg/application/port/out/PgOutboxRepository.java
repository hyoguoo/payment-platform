package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * pg-service outbound 포트 — outbox 저장소 계약.
 * Transactional Outbox 패턴 + available_at 기반 지연 발행.
 */
public interface PgOutboxRepository {

    PgOutbox save(PgOutbox outbox);

    Optional<PgOutbox> findById(long id);

    /**
     * processedAt=null AND availableAt <= now 조건의 pending row를 batchSize 개 반환.
     */
    List<PgOutbox> findPendingBatch(int batchSize, Instant now);

    void markProcessed(long id, Instant processedAt);

    // ── 관측 지표 집계 (Prometheus gauge) ───────────────────────────────────────

    /**
     * processedAt=null AND availableAt &lt;= now 인 row 수를 반환한다 (현재 처리 가능한 pending).
     */
    long countPending(Instant now);

    /**
     * processedAt=null AND availableAt &gt; now 인 row 수를 반환한다 (미래 예약 pending).
     */
    long countFuturePending(Instant now);

    /**
     * processedAt=null 인 row 중 가장 오래된 createdAt을 반환한다.
     * pending row가 없으면 Optional.empty().
     */
    Optional<Instant> findOldestPendingCreatedAt();

    // ── 관리자 이력 조회 ─────────────────────────────────────────────────────────

    /**
     * 주문번호(key) 기준 확정 시도 이력 행을 created_at 오름차순으로 반환한다.
     * 토픽은 확정 명령({@link com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics#COMMANDS_CONFIRM})
     * 과 소진({@link com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics#COMMANDS_CONFIRM_DLQ})
     * 만 대상으로 한다 — 결과 발행 토픽
     * ({@link com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics#EVENTS_CONFIRMED}) 행은
     * 재발행 서비스({@code PgTerminalReemitService})가 만든 행이라 시도가 아니므로 제외한다.
     */
    List<PgOutbox> findConfirmAttemptRows(String orderId);
}
