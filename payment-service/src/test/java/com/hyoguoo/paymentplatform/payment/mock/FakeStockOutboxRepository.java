package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * StockOutboxRepository Fake — 단위 테스트용 in-memory 구현. stock outbox 패턴 테스트 지원.
 */
public class FakeStockOutboxRepository implements StockOutboxRepository {

    private final Map<Long, StockOutbox> store = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1L);

    @Override
    public StockOutbox save(StockOutbox stockOutbox) {
        Long id = stockOutbox.getId() != null ? stockOutbox.getId() : idSeq.getAndIncrement();
        StockOutbox withId = StockOutbox.of(
                id,
                stockOutbox.getTopic(),
                stockOutbox.getKey(),
                stockOutbox.getPayload(),
                stockOutbox.getHeadersJson(),
                stockOutbox.getAvailableAt(),
                stockOutbox.getProcessedAt(),
                stockOutbox.getAttempt(),
                stockOutbox.getCreatedAt()
        );
        store.put(id, withId);
        return withId;
    }

    @Override
    public Optional<StockOutbox> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void markProcessed(Long id, LocalDateTime processedAt) {
        StockOutbox existing = store.get(id);
        if (existing != null) {
            existing.markProcessed(processedAt);
        }
    }

    @Override
    public List<StockOutbox> findPendingBatch(int batchSize) {
        return store.values().stream()
                .filter(StockOutbox::isPending)
                .sorted(java.util.Comparator.comparingLong(StockOutbox::getId))
                .limit(batchSize)
                .toList();
    }

    // --- test assertion helpers ---

    public int savedCount() {
        return store.size();
    }

    public List<StockOutbox> allSaved() {
        return List.copyOf(store.values());
    }

    public void clear() {
        store.clear();
        idSeq.set(1L);
    }
}
