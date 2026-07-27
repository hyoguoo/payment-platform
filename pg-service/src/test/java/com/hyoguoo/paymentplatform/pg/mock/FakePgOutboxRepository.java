package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PgOutboxRepository Fake — DB 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: ConcurrentHashMap.
 * available_at 기반 지연 발행 조건을 인메모리에서 그대로 재현한다.
 * id=null 인 경우 auto-increment ID 를 생성한다 (실제 DB auto-generated ID 모사).
 */
public class FakePgOutboxRepository implements PgOutboxRepository {

    private static final List<String> CONFIRM_ATTEMPT_TOPICS =
            List.of(PgTopics.COMMANDS_CONFIRM, PgTopics.COMMANDS_CONFIRM_DLQ);

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
     * processedAt=null AND availableAt &lt;= now 인 row 를 batchSize 개 반환한다.
     * available_at 기반 지연 발행 필터링.
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

    /**
     * 주문번호(key) 기준 확정 시도 이력 행을 created_at 오름차순으로 반환한다.
     * 확정 명령/소진 토픽만 대상 — 결과 발행 토픽 행은 재발행 서비스가 만든 행이라 제외한다.
     * 프로덕션 PgOutboxRepositoryImpl.findConfirmAttemptRows 와 동일 필터·정렬 조건을 재현한다.
     */
    @Override
    public List<PgOutbox> findConfirmAttemptRows(String orderId) {
        return store.values().stream()
                .filter(o -> o.getKey().equals(orderId) && CONFIRM_ATTEMPT_TOPICS.contains(o.getTopic()))
                .sorted(Comparator.comparing(PgOutbox::getCreatedAt))
                .toList();
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
