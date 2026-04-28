package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgOutboxEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PgOutboxRepository 포트 JPA 어댑터.
 * Instant ↔ LocalDateTime(UTC) 변환을 담당한다.
 */
@Repository
@RequiredArgsConstructor
public class PgOutboxRepositoryImpl implements PgOutboxRepository {

    private final JpaPgOutboxRepository jpaPgOutboxRepository;

    @Override
    public PgOutbox save(PgOutbox outbox) {
        return jpaPgOutboxRepository.save(PgOutboxEntity.from(outbox)).toDomain();
    }

    @Override
    public Optional<PgOutbox> findById(long id) {
        return jpaPgOutboxRepository.findById(id).map(PgOutboxEntity::toDomain);
    }

    @Override
    public List<PgOutbox> findPendingBatch(int batchSize, Instant now) {
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        return jpaPgOutboxRepository
                .findPendingBatch(nowLdt, PageRequest.of(0, batchSize, Sort.unsorted()))
                .stream()
                .map(PgOutboxEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void markProcessed(long id, Instant processedAt) {
        jpaPgOutboxRepository.markProcessed(id, LocalDateTime.ofInstant(processedAt, ZoneOffset.UTC));
    }

    @Override
    public long countPending(Instant now) {
        return jpaPgOutboxRepository.countPending(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    public long countFuturePending(Instant now) {
        return jpaPgOutboxRepository.countFuturePending(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    @Override
    public Optional<Instant> findOldestPendingCreatedAt() {
        return jpaPgOutboxRepository.findOldestPendingCreatedAt()
                .map(ldt -> ldt.toInstant(ZoneOffset.UTC));
    }
}
