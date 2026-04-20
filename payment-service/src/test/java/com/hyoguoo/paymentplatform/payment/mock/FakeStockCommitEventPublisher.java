package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * StockCommitEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: CopyOnWriteArrayList + AtomicReference.
 */
public class FakeStockCommitEventPublisher implements StockCommitEventPublisherPort {

    public record StockCommittedRecord(
            Long productId,
            int qty,
            String idempotencyKey,
            LocalDateTime timestamp
    ) {

    }

    private final List<StockCommittedRecord> published = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();

    @Override
    public void publish(Long productId, int qty, String idempotencyKey) {
        Throwable failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throwUnchecked(failure);
        }
        published.add(new StockCommittedRecord(productId, qty, idempotencyKey, LocalDateTime.now()));
    }

    // --- assertion helpers ---

    public List<StockCommittedRecord> publishedEvents() {
        return List.copyOf(published);
    }

    public Optional<StockCommittedRecord> lastEvent() {
        if (published.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(published.get(published.size() - 1));
    }

    public long countFor(Long productId) {
        return published.stream()
                .filter(r -> r.productId().equals(productId))
                .count();
    }

    public void clear() {
        published.clear();
        nextFailure.set(null);
    }

    // --- failure simulation ---

    /**
     * 다음 publish() 한 번만 예외를 던진다.
     */
    public void failNext() {
        nextFailure.set(new RuntimeException("FakeStockCommitEventPublisher: simulated failure"));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
