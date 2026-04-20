package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MessagePublisherPort Fake — Kafka 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: CopyOnWriteArrayList + AtomicReference.
 */
public class FakeMessagePublisher implements MessagePublisherPort {

    public record SentMessage(String topic, String key, Object payload, LocalDateTime timestamp) {

    }

    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicReference<Throwable> nextFailure = new AtomicReference<>();
    private volatile Throwable permanentFailure;

    @Override
    public void send(String topic, String key, Object payload) {
        Throwable failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throwUnchecked(failure);
        }
        if (permanentFailure != null) {
            throwUnchecked(permanentFailure);
        }
        sent.add(new SentMessage(topic, key, payload, LocalDateTime.now()));
    }

    // --- assertion helpers ---

    public List<SentMessage> findByTopic(String topic) {
        return sent.stream()
                .filter(m -> m.topic().equals(topic))
                .toList();
    }

    public Optional<SentMessage> lastMessage() {
        if (sent.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(sent.get(sent.size() - 1));
    }

    public int count() {
        return sent.size();
    }

    public void clear() {
        sent.clear();
        nextFailure.set(null);
        permanentFailure = null;
    }

    // --- failure simulation ---

    /**
     * 다음 send() 한 번만 예외를 던진다.
     */
    public void failNext() {
        nextFailure.set(new RuntimeException("FakeMessagePublisher: simulated failure"));
    }

    /**
     * 특정 예외를 다음 send() 한 번만 던진다.
     */
    public void setFailure(Throwable throwable) {
        nextFailure.set(throwable);
    }

    /**
     * 이후 모든 send() 호출마다 예외를 던진다. null 전달 시 비활성화.
     */
    public void setPermanentFailure(Throwable throwable) {
        permanentFailure = throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable t) throws T {
        throw (T) t;
    }
}
