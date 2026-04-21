package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StockRestoreEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 * stock.events.restore 토픽 발행 검증.
 *
 * <p>Thread-safe: CopyOnWriteArrayList 기반.
 */
public class FakeStockRestoreEventPublisher implements StockRestoreEventPublisherPort {

    public record StockRestoredRecord(
            String orderId,
            List<Long> productIds
    ) {

    }

    private final List<StockRestoredRecord> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger(0);

    @Override
    public void publish(String orderId, List<Long> productIds) {
        callCount.incrementAndGet();
        published.add(new StockRestoredRecord(orderId, productIds));
    }

    // --- assertion helpers ---

    public int publishedCount() {
        return callCount.get();
    }

    public List<StockRestoredRecord> publishedEvents() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
        callCount.set(0);
    }
}
