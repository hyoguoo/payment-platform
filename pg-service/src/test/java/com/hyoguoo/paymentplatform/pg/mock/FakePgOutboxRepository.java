package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PgOutboxRepository Fake — DB 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: ConcurrentHashMap.
 * ADR-30: available_at 기반 지연 발행 조건을 인메모리에서 그대로 재현.
 * id=null인 경우 auto-increment ID를 생성한다 (실제 DB auto-generated ID 모사).
 */
public class FakePgOutboxRepository implements PgOutboxRepository {

    private final ConcurrentHashMap<Long, PgOutbox> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1L);

    @Override
    public PgOutbox save(PgOutbox outbox) {
        if (outbox.getId() == null) {
            // id=null → auto-increment ID 부여 (DB auto-generated ID 모사)
            long newId = idSequence.getAndIncrement();
            PgOutbox withId = PgOutbox.of(
                    newId,
                    outbox.getTopic(),
                    outbox.getKey(),
                    outbox.getPayload(),
                    outbox.getHeadersJson(),
                    outbox.getAvailableAt(),
                    outbox.getProcessedAt(),
                    outbox.getAttempt(),
                    outbox.getCreatedAt());
            store.put(newId, withId);
            return withId;
        }
        store.put(outbox.getId(), outbox);
        return outbox;
    }

    @Override
    public Optional<PgOutbox> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * processedAt=null AND availableAt <= now 인 row를 batchSize 개 반환한다.
     * ADR-30: available_at 기반 지연 발행 필터링.
     */
    @Override
    public List<PgOutbox> findPendingBatch(int batchSize, Instant now) {
        List<PgOutbox> result = new ArrayList<>();
        for (PgOutbox outbox : store.values()) {
            if (outbox.isPending() && outbox.isAvailableAt(now)) {
                result.add(outbox);
                if (result.size() >= batchSize) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void markProcessed(long id, Instant processedAt) {
        PgOutbox outbox = store.get(id);
        if (outbox != null) {
            outbox.markProcessed(processedAt);
        }
    }

    @Override
    public long countPending(Instant now) {
        return store.values().stream()
                .filter(o -> o.isPending() && o.isAvailableAt(now))
                .count();
    }

    @Override
    public long countFuturePending(Instant now) {
        return store.values().stream()
                .filter(o -> o.isPending() && !o.isAvailableAt(now))
                .count();
    }

    @Override
    public Optional<Instant> findOldestPendingCreatedAt() {
        return store.values().stream()
                .filter(PgOutbox::isPending)
                .map(PgOutbox::getCreatedAt)
                .min(Instant::compareTo);
    }

    // --- 검증 헬퍼 ---

    public int size() {
        return store.size();
    }

    public List<PgOutbox> findAll() {
        return List.copyOf(store.values());
    }

    // --- 초기화 ---

    public void reset() {
        store.clear();
        idSequence.set(1L);
    }
}
