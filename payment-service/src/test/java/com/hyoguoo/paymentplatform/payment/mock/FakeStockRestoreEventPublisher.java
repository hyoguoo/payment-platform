package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StockRestoreEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 * stock.events.restore 토픽 발행 검증.
 *
 * <p>Thread-safe: CopyOnWriteArrayList 기반.
 * <p>publishPayload: eventUUID 기반 멱등 — 동일 UUID 재호출 시 no-op (ADR-16 검증).
 */
public class FakeStockRestoreEventPublisher implements StockRestoreEventPublisherPort {

    public record StockRestoredRecord(
            String orderId,
            List<Long> productIds
    ) {

    }

    private final List<StockRestoredRecord> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger(0);

    /** publishPayload 호출로 저장된 payload 목록 (UUID 멱등 적용). */
    private final List<StockRestoreEventPayload> publishedPayloads = new CopyOnWriteArrayList<>();
    private final List<UUID> seenUuids = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String orderId, List<Long> productIds) {
        callCount.incrementAndGet();
        published.add(new StockRestoredRecord(orderId, productIds));
    }

    @Override
    public void publishPayload(StockRestoreEventPayload payload) {
        // ADR-16: 동일 eventUUID 재호출 시 no-op (멱등성 시뮬레이션)
        if (seenUuids.contains(payload.eventUUID())) {
            return;
        }
        seenUuids.add(payload.eventUUID());
        publishedPayloads.add(payload);
    }

    // --- assertion helpers ---

    public int publishedCount() {
        return callCount.get();
    }

    public List<StockRestoredRecord> publishedEvents() {
        return List.copyOf(published);
    }

    public List<StockRestoreEventPayload> publishedPayloads() {
        return List.copyOf(publishedPayloads);
    }

    public void clear() {
        published.clear();
        callCount.set(0);
        publishedPayloads.clear();
        seenUuids.clear();
    }
}
