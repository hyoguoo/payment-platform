package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * StockRestoreEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 * stock.events.restore 토픽 발행 검증.
 *
 * <p>Thread-safe: CopyOnWriteArrayList 기반.
 * <p>publishPayload: eventUUID 기반 멱등 — 동일 UUID 재호출 시 no-op (ADR-16 검증).
 *
 * <p>T-B2: qty=0 플레이스홀더 경로였던 publish(orderId, List&lt;Long&gt;) 오버로드 철거.
 * publishPayload(StockRestoreEventPayload)만 남는다.
 */
public class FakeStockRestoreEventPublisher implements StockRestoreEventPublisherPort {

    /** publishPayload 호출로 저장된 payload 목록 (UUID 멱등 적용). */
    private final List<StockRestoreEventPayload> publishedPayloads = new CopyOnWriteArrayList<>();
    private final List<UUID> seenUuids = new CopyOnWriteArrayList<>();

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

    public List<StockRestoreEventPayload> publishedPayloads() {
        return List.copyOf(publishedPayloads);
    }

    public void clear() {
        publishedPayloads.clear();
        seenUuids.clear();
    }
}
