package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Instant;
import java.util.List;

/**
 * pg-service outbound 포트 — outbox 저장소 계약.
 * ADR-04: Transactional Outbox 패턴, ADR-30: available_at 지연 발행.
 * 구현체(JPA 어댑터)는 T2a-04에서 추가.
 */
public interface PgOutboxRepository {

    PgOutbox save(PgOutbox outbox);

    /**
     * processedAt=null AND availableAt <= now 조건의 pending row를 batchSize 개 반환.
     */
    List<PgOutbox> findPendingBatch(int batchSize, Instant now);

    void markProcessed(long id, Instant processedAt);
}
