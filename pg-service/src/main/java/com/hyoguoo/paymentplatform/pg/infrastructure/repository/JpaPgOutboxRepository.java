package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA — pg_outbox 테이블 접근.
 * Transactional Outbox + available_at 기반 지연 발행.
 */
public interface JpaPgOutboxRepository extends JpaRepository<PgOutboxEntity, Long> {

    /**
     * processedAt=null AND availableAt <= :now 인 row를 batchSize 개 반환.
     * createdAt ASC 정렬로 FIFO 발행 보장.
     */
    @Query("SELECT e FROM PgOutboxEntity e "
            + "WHERE e.processedAt IS NULL AND e.availableAt <= :now "
            + "ORDER BY e.createdAt ASC")
    List<PgOutboxEntity> findPendingBatch(@Param("now") LocalDateTime now, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgOutboxEntity e SET e.processedAt = :processedAt WHERE e.id = :id")
    int markProcessed(@Param("id") long id, @Param("processedAt") LocalDateTime processedAt);

    @Query("SELECT COUNT(e) FROM PgOutboxEntity e "
            + "WHERE e.processedAt IS NULL AND e.availableAt <= :now")
    long countPending(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(e) FROM PgOutboxEntity e "
            + "WHERE e.processedAt IS NULL AND e.availableAt > :now")
    long countFuturePending(@Param("now") LocalDateTime now);

    @Query("SELECT MIN(e.createdAt) FROM PgOutboxEntity e WHERE e.processedAt IS NULL")
    Optional<LocalDateTime> findOldestPendingCreatedAt();
}
