package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PgEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: CopyOnWriteArrayList.
 * 발행된 이벤트를 캡처하고 assertion helper를 제공한다.
 */
public class FakePgEventPublisher implements PgEventPublisherPort {

    public record EventCapture(String orderId, String status, String reasonCode, String eventUuid) {

    }

    private final CopyOnWriteArrayList<EventCapture> published = new CopyOnWriteArrayList<>();

    @Override
    public void publishConfirmed(String orderId, String status, String reasonCode, String eventUuid) {
        published.add(new EventCapture(orderId, status, reasonCode, eventUuid));
    }

    // --- 검증 헬퍼 ---

    public int getPublishedCount() {
        return published.size();
    }

    public Optional<EventCapture> getLast() {
        if (published.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(published.get(published.size() - 1));
    }

    public List<EventCapture> findByOrderId(String orderId) {
        return published.stream()
                .filter(e -> e.orderId().equals(orderId))
                .toList();
    }

    public List<EventCapture> getAll() {
        return List.copyOf(published);
    }

    // --- 초기화 ---

    public void reset() {
        published.clear();
    }
}
