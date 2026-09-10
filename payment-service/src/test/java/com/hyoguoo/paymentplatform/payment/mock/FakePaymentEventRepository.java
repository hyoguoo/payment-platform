package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AtomicInteger findByOrderIdCount = new AtomicInteger(0);
    private final AtomicInteger findByOrderIdForUpdateCount = new AtomicInteger(0);

    /** saveOrUpdate 직접 호출 횟수 — PaymentCommandUseCase 위임 검증용. */
    public int saveOrUpdateCallCount() {
        return saveOrUpdateCount.get();
    }

    /** 잠금 없는 findByOrderId 호출 횟수 — 소비 경로가 잠금 읽기를 쓰는지 검증용. */
    public int findByOrderIdCallCount() {
        return findByOrderIdCount.get();
    }

    /** 잠금 읽기 findByOrderIdForUpdate 호출 횟수 — 소비 경로가 잠금 읽기를 쓰는지 검증용. */
    public int findByOrderIdForUpdateCallCount() {
        return findByOrderIdForUpdateCount.get();
    }

    /**
     * 저장 시 인자 객체를 그대로 참조하지 않고 방어적으로 복사해 저장한다.
     *
     * <p>{@link PaymentEvent#resetToAwaitingResult} / {@link PaymentEvent#quarantine} 등 도메인 전이
     * 메서드는 대상 객체를 in-place 로 뮤테이트한다. 위임 메서드({@code PaymentCommandUseCase}
     * 의 CAS 계열)는 이 뮤테이트를 먼저 적용한 뒤 조건부 갱신을 호출하는 순서라, 저장 시 같은
     * 참조를 그대로 들고 있으면 CAS 선행조건 검사({@link #resolveInProgressToAwaitingResult} 등)가
     * 이미 뮤테이트된 값을 보게 되어 정상 케이스도 항상 충돌로 오판한다. 복사로 저장소의 상태를
     * 호출자가 들고 있는 참조와 분리해, CAS 검사가 저장 시점의 상태를 기준으로 판정하게 한다.
     */
    public void save(PaymentEvent event) {
        store.put(event.getOrderId(), copyOf(event));
    }

    private static PaymentEvent copyOf(PaymentEvent source) {
        return PaymentEvent.allArgsBuilder()
                .id(source.getId())
                .buyerId(source.getBuyerId())
                .sellerId(source.getSellerId())
                .orderName(source.getOrderName())
                .orderId(source.getOrderId())
                .paymentKey(source.getPaymentKey())
                .gatewayType(source.getGatewayType())
                .status(source.getStatus())
                .executedAt(source.getExecutedAt())
                .approvedAt(source.getApprovedAt())
                .statusReason(source.getStatusReason())
                .paymentOrderList(new ArrayList<>(source.getPaymentOrderList()))
                .createdAt(source.getCreatedAt())
                .lastStatusChangedAt(source.getLastStatusChangedAt())
                .allArgsBuild();
    }

    @Override
    public Optional<PaymentEvent> findById(Long id) {
        return store.values().stream()
                .filter(e -> e.getId() != null && e.getId().equals(id))
                .findFirst()
                .map(FakePaymentEventRepository::copyOf);
    }

    @Override
    public Optional<PaymentEvent> findByOrderId(String orderId) {
        findByOrderIdCount.incrementAndGet();
        return Optional.ofNullable(store.get(orderId)).map(FakePaymentEventRepository::copyOf);
    }

    /**
     * 실제 구현({@code PaymentEventRepositoryImpl})의 잠금 읽기(FOR UPDATE)를 in-memory 로 재현한다.
     * 동시성 자체는 흉내 내지 않고(단일 스레드 테스트 목적), 호출 여부·횟수만 구분한다.
     */
    @Override
    public Optional<PaymentEvent> findByOrderIdForUpdate(String orderId) {
        findByOrderIdForUpdateCount.incrementAndGet();
        return Optional.ofNullable(store.get(orderId)).map(FakePaymentEventRepository::copyOf);
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
                .map(FakePaymentEventRepository::copyOf)
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
                .map(FakePaymentEventRepository::copyOf)
                .toList();
    }

    @Override
    public List<PaymentEvent> findAllByStatus(PaymentEventStatus status) {
        return store.values().stream()
                .filter(e -> e.getStatus() == status)
                .map(FakePaymentEventRepository::copyOf)
                .toList();
    }

    /**
     * 아래 {@code resolve*} 3종은 내부에서 {@link #findById} 를 거쳐 대상을 조회한다 — 그 조회는
     * 이제 저장소와 분리된 복사본을 돌려주므로, 호출자가 {@code findInProgressOlderThan} 등으로 먼저
     * 받아 둔 객체를 뮤테이트해도 이 조회 결과에는 반영되지 않는다(그래서 선행조건 검사가 저장 시점의
     * 실제 상태를 기준으로 판정한다). 찾은 복사본은 여기서 다시 복사하지 않고 그 자리에서 직접
     * 뮤테이트한 뒤 {@code store.put} 으로 되쓴다 — 이미 저장소와 분리된 사본이라 한 번 더 복사할
     * 이유가 없고, 이 메서드들 자체가 쓰기 경로의 종착점이다.
     *
     * <p>실제 구현({@code PaymentEventRepositoryImpl})의 DB CAS 게이트를 in-memory 로 재현한다.
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

    /**
     * 실제 구현({@code PaymentEventRepositoryImpl})의 DB CAS 게이트를 in-memory 로 재현한다.
     * 저장소의 현재 상태가 IN_PROGRESS 일 때만 {@link PaymentEvent#resetToAwaitingResult} 도메인 전이를
     * 적용해 true 를 반환하고, 그 외에는 아무 것도 바꾸지 않고 false 를 반환한다.
     */
    @Override
    public boolean resolveInProgressToAwaitingResult(Long paymentEventId, Instant lastStatusChangedAt) {
        Optional<PaymentEvent> found = findById(paymentEventId);
        if (found.isEmpty() || found.get().getStatus() != PaymentEventStatus.IN_PROGRESS) {
            return false;
        }
        PaymentEvent event = found.get();
        event.resetToAwaitingResult(lastStatusChangedAt);
        store.put(event.getOrderId(), event);
        return true;
    }

    /**
     * 실제 구현({@code PaymentEventRepositoryImpl})의 DB CAS 게이트를 in-memory 로 재현한다.
     * 저장소의 현재 상태가 AWAITING_RESULT 일 때만 {@link PaymentEvent#quarantine} 도메인 전이를
     * 적용해 true 를 반환하고, 그 외에는 아무 것도 바꾸지 않고 false 를 반환한다.
     */
    @Override
    public boolean resolveAwaitingResultToQuarantine(Long paymentEventId, String reason, Instant lastStatusChangedAt) {
        Optional<PaymentEvent> found = findById(paymentEventId);
        if (found.isEmpty() || found.get().getStatus() != PaymentEventStatus.AWAITING_RESULT) {
            return false;
        }
        PaymentEvent event = found.get();
        event.quarantine(reason, lastStatusChangedAt);
        store.put(event.getOrderId(), event);
        return true;
    }

    @Override
    public List<PaymentEvent> findAwaitingResultOlderThan(Instant before) {
        return store.values().stream()
                .filter(e -> e.getStatus() == PaymentEventStatus.AWAITING_RESULT)
                .filter(e -> e.getLastStatusChangedAt() != null && e.getLastStatusChangedAt().isBefore(before))
                .map(FakePaymentEventRepository::copyOf)
                .toList();
    }
}
