package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PgEventPublisherPort Fake — Kafka 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: CopyOnWriteArrayList.
 * 발행된 이벤트를 캡처하고 assertion helper를 제공한다.
 */
public class FakePgEventPublisher implements PgEventPublisherPort {

    public record EventCapture(String topic, String key, Object payload, Map<String, byte[]> headers) {

    }

    private final CopyOnWriteArrayList<EventCapture> published = new CopyOnWriteArrayList<>();
    private boolean failOnPublish = false;

    @Override
    public void publish(String topic, String key, Object payload, Map<String, byte[]> headers) {
        if (failOnPublish) {
            throw new RuntimeException("Fake: Kafka 발행 실패 시뮬레이션");
        }
        published.add(new EventCapture(topic, key, payload, headers));
    }

    // --- 실패 시뮬레이션 ---

    public void setFailOnPublish(boolean fail) {
        this.failOnPublish = fail;
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

    public List<EventCapture> findByTopic(String topic) {
        return published.stream()
                .filter(e -> e.topic().equals(topic))
                .toList();
    }

    public List<EventCapture> getAll() {
        return List.copyOf(published);
    }

    // --- 초기화 ---

    public void reset() {
        published.clear();
        failOnPublish = false;
    }
}
