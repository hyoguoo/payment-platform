package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.Instant;
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
    public List<PaymentEvent> findReadyPaymentsOlderThan(Instant before) {
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
    public long countByStatusAndExecutedAtBefore(PaymentEventStatus status, Instant before) {
        return store.values().stream()
                .filter(e -> e.getStatus() == status)
                .filter(e -> e.getExecutedAt() != null && e.getExecutedAt().isBefore(before))
                .count();
    }

    @Override
    public List<PaymentEvent> findInProgressOlderThan(Instant before) {
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

    /**
     * 실제 구현({@code PaymentEventRepositoryImpl})의 DB CAS 게이트를 in-memory 로 재현한다.
     * 저장소의 현재 상태가 QUARANTINED 일 때만 {@link PaymentEvent#failFromQuarantine} 도메인 전이를
     * 그 자리에서 적용해(자식 order 도 함께 FAIL) true 를 반환하고, 그 외에는 아무 것도 바꾸지 않고 false 를
     * 반환한다.
     */
    @Override
    public boolean resolveQuarantineToFailed(Long paymentEventId, String reason, Instant lastStatusChangedAt) {
        Optional<PaymentEvent> found = findById(paymentEventId);
        if (found.isEmpty() || found.get().getStatus() != PaymentEventStatus.QUARANTINED) {
            return false;
        }
        PaymentEvent event = found.get();
        event.failFromQuarantine(reason, lastStatusChangedAt);
        store.put(event.getOrderId(), event);
        return true;
    }
}
