package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PaymentEventRepository Fake — in-memory 구현체.
 * ConfirmedEventConsumerTest 등 application 계층 테스트에서 사용.
 *
 * <p>Thread-safe: ConcurrentHashMap 기반.
 */
public class FakePaymentEventRepository implements PaymentEventRepository {

    private final Map<String, PaymentEvent> store = new ConcurrentHashMap<>();
    private final AtomicInteger saveOrUpdateCount = new AtomicInteger(0);

    /** saveOrUpdate 직접 호출 횟수 — PaymentCommandUseCase 위임 검증용. */
    public int saveOrUpdateCallCount() {
        return saveOrUpdateCount.get();
    }

    public void save(PaymentEvent event) {
        store.put(event.getOrderId(), event);
    }

    @Override
    public Optional<PaymentEvent> findById(Long id) {
        return store.values().stream()
                .filter(e -> e.getId() != null && e.getId().equals(id))
                .findFirst();
    }

    @Override
    public Optional<PaymentEvent> findByOrderId(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public PaymentEvent saveOrUpdate(PaymentEvent paymentEvent) {
        saveOrUpdateCount.incrementAndGet();
        store.put(paymentEvent.getOrderId(), paymentEvent);
        return paymentEvent;
    }

    @Override
    public List<PaymentEvent> findReadyPaymentsOlderThan(LocalDateTime before) {
        return store.values().stream()
                .filter(e -> e.getStatus() == PaymentEventStatus.READY)
                .filter(e -> e.getCreatedAt() != null && e.getCreatedAt().isBefore(before))
                .toList();
    }

    @Override
    public Map<PaymentEventStatus, Long> countByStatus() {
        Map<PaymentEventStatus, Long> result = new ConcurrentHashMap<>();
        for (PaymentEvent e : store.values()) {
            result.merge(e.getStatus(), 1L, Long::sum);
        }
        return result;
    }

    @Override
    public long countByStatusAndExecutedAtBefore(PaymentEventStatus status, LocalDateTime before) {
        return store.values().stream()
                .filter(e -> e.getStatus() == status)
                .filter(e -> e.getExecutedAt() != null && e.getExecutedAt().isBefore(before))
                .count();
    }

    @Override
    public long countByRetryCountGreaterThanEqual(int retryCount) {
        return store.values().stream()
                .filter(e -> e.getRetryCount() != null && e.getRetryCount() >= retryCount)
                .count();
    }

    @Override
    public List<PaymentEvent> findInProgressOlderThan(LocalDateTime before) {
        return store.values().stream()
                .filter(e -> e.getStatus() == PaymentEventStatus.IN_PROGRESS)
                .filter(e -> e.getExecutedAt() != null && e.getExecutedAt().isBefore(before))
                .toList();
    }

    @Override
    public List<PaymentEvent> findAllByStatus(PaymentEventStatus status) {
        return store.values().stream()
                .filter(e -> e.getStatus() == status)
                .toList();
    }
}
