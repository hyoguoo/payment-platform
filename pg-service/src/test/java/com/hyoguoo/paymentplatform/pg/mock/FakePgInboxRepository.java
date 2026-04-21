package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PgInboxRepository Fake — DB 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: ConcurrentHashMap.
 * orderId를 키로 단일 inbox 행을 관리한다 (ADR-21: UNIQUE 제약 반영).
 */
public class FakePgInboxRepository implements PgInboxRepository {

    private final ConcurrentHashMap<String, PgInbox> store = new ConcurrentHashMap<>();

    @Override
    public Optional<PgInbox> findByOrderId(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public PgInbox save(PgInbox inbox) {
        store.put(inbox.getOrderId(), inbox);
        return inbox;
    }

    /**
     * NONE → IN_PROGRESS 원자 compare-and-set.
     * ADR-04: 동시 진입 시 단 1 스레드만 true를 반환한다.
     *
     * <p>구현 방식:
     * <ul>
     *   <li>row가 없는 경우: putIfAbsent로 IN_PROGRESS row를 생성 — 성공(null 반환)이면 true.</li>
     *   <li>row가 있는 경우: 현재 상태가 NONE인지 확인 후 compare-and-set 전이 시도.
     *       NONE이 아니면 false 즉시 반환.</li>
     * </ul>
     */
    @Override
    public boolean transitNoneToInProgress(String orderId, long amount) {
        Instant now = Instant.now();
        PgInbox inProgress = PgInbox.of(
                orderId, PgInboxStatus.IN_PROGRESS, amount,
                null, null, now, now);

        // row가 없는 경우 — putIfAbsent로 원자 생성
        PgInbox existing = store.putIfAbsent(orderId, inProgress);
        if (existing == null) {
            return true; // 새 row 생성 성공
        }

        // row가 있는 경우 — NONE 상태인 경우만 IN_PROGRESS 전이 시도
        AtomicBoolean transitioned = new AtomicBoolean(false);
        store.compute(orderId, (key, current) -> {
            if (current != null && current.getStatus() == PgInboxStatus.NONE) {
                transitioned.set(true);
                return inProgress;
            }
            return current; // 상태 유지 (전이 실패)
        });
        return transitioned.get();
    }

    @Override
    public void transitToApproved(String orderId, String storedStatusResult) {
        store.compute(orderId, (key, current) -> {
            if (current == null) {
                return null;
            }
            return current.withResult(PgInboxStatus.APPROVED, storedStatusResult, null);
        });
    }

    @Override
    public void transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
        store.compute(orderId, (key, current) -> {
            if (current == null) {
                return null;
            }
            return current.withResult(PgInboxStatus.FAILED, storedStatusResult, reasonCode);
        });
    }

    /**
     * non-terminal → QUARANTINED 원자 compare-and-set.
     * 이미 terminal이면 false 반환 (불변식 6c 중복 DLQ 흡수).
     */
    @Override
    public boolean transitToQuarantined(String orderId, String reasonCode) {
        AtomicBoolean transitioned = new AtomicBoolean(false);
        store.compute(orderId, (key, current) -> {
            if (current == null || current.getStatus().isTerminal()) {
                return current; // 이미 terminal 또는 미존재 → no-op
            }
            transitioned.set(true);
            return current.withResult(PgInboxStatus.QUARANTINED, null, reasonCode);
        });
        return transitioned.get();
    }

    /**
     * FOR UPDATE 잠금 조회 — Fake에서는 일반 findByOrderId와 동등.
     */
    @Override
    public Optional<PgInbox> findByOrderIdForUpdate(String orderId) {
        return findByOrderId(orderId);
    }

    // --- 검증 헬퍼 ---

    public int size() {
        return store.size();
    }

    public List<PgInbox> findAll() {
        Collection<PgInbox> values = store.values();
        return List.copyOf(values);
    }

    // --- 초기화 ---

    public void reset() {
        store.clear();
    }
}
