package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PgInboxRepository Fake — DB 없이 application 계층 테스트용.
 *
 * <p>Thread-safe: ConcurrentHashMap.
 * orderId 를 키로 단일 inbox 행을 관리한다 (UNIQUE 제약 반영).
 * id 기반 조회를 위해 id → orderId 역인덱스를 둔다.
 */
public class FakePgInboxRepository implements PgInboxRepository {

    private final ConcurrentHashMap<String, PgInbox> store = new ConcurrentHashMap<>();
    /** id → orderId 역인덱스 — findById 지원용 */
    private final ConcurrentHashMap<Long, String> idIndex = new ConcurrentHashMap<>();
    /** id → storedTraceparent 인덱스 — findStoredTraceparent 지원용 */
    private final ConcurrentHashMap<Long, String> traceparentIndex = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    @Override
    public Optional<PgInbox> findByOrderId(String orderId) {
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public Optional<PgInbox> findById(Long inboxId) {
        String orderId = idIndex.get(inboxId);
        if (orderId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(orderId));
    }

    @Override
    public PgInbox save(PgInbox inbox) {
        // orderId 로 먼저 찾아 기존 id 재사용, 없으면 신규 id 발급
        store.compute(inbox.getOrderId(), (key, existing) -> {
            if (existing == null) {
                Long newId = idSequence.getAndIncrement();
                idIndex.put(newId, inbox.getOrderId());
            }
            return inbox;
        });
        return inbox;
    }

    @Override
    public void transitToApproved(String orderId, String storedStatusResult) {
        store.computeIfPresent(orderId, (key, current) -> {
            current.markApproved(storedStatusResult, java.time.Instant.now());
            return current;
        });
    }

    @Override
    public void transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
        store.computeIfPresent(orderId, (key, current) -> {
            current.markFailed(storedStatusResult, reasonCode, java.time.Instant.now());
            return current;
        });
    }

    /**
     * non-terminal → QUARANTINED 원자 compare-and-set.
     * 이미 terminal이면 false 반환 (중복 DLQ 흡수).
     */
    @Override
    public boolean transitToQuarantined(String orderId, String reasonCode) {
        AtomicBoolean transitioned = new AtomicBoolean(false);
        store.computeIfPresent(orderId, (key, current) -> {
            if (current.getStatus().isTerminal()) {
                return current; // 이미 terminal → no-op
            }
            current.markQuarantined(null, reasonCode, java.time.Instant.now());
            transitioned.set(true);
            return current;
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

    /**
     * listener 경로 PENDING INSERT — orderId 충돌 시 기존 id 반환.
     * paymentKey / vendorType / storedTraceparent 포함 저장 (V4 migration 정합).
     * storedTraceparent 는 NULL 허용 — 헤더 부재·구버전 행 호환.
     */
    @Override
    public Long insertPending(String orderId, long amount,
                              String vendorType, String paymentKey, String storedTraceparent) {
        // orderId 충돌 시 기존 row 유지, 신규 row id 발급 없음
        AtomicBoolean[] inserted = {new AtomicBoolean(false)};
        store.computeIfAbsent(orderId, key -> {
            Long newId = idSequence.getAndIncrement();
            PgInbox inbox = PgInbox.of(orderId, PgInboxStatus.PENDING, amount,
                    null, null, java.time.Instant.now(), java.time.Instant.now(),
                    paymentKey, vendorType);
            idIndex.put(newId, orderId);
            if (storedTraceparent != null) {
                traceparentIndex.put(newId, storedTraceparent);
            }
            inserted[0].set(true);
            // 실제 저장은 compute 으로 진행
            return inbox;
        });
        // id 조회 — store 에서 orderId로 찾은 후 idIndex 역방향
        PgInbox inbox = store.get(orderId);
        if (inbox == null) {
            return null;
        }
        // idIndex에서 orderId에 대응하는 id를 역방향 검색
        return idIndex.entrySet().stream()
                .filter(e -> e.getValue().equals(orderId))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Override
    public Optional<String> findStoredTraceparent(Long inboxId) {
        return Optional.ofNullable(traceparentIndex.get(inboxId));
    }

    /**
     * 워커 TX_A — PENDING → IN_PROGRESS SKIP LOCKED (Fake 단순 구현).
     * 동시성 없이 PENDING row 가 존재하면 IN_PROGRESS 전이.
     *
     * <p>{@code markInProgress} (도메인 mutate) 사용 — attempt 보존(self-loop 시뮬 전제).
     * 과거 {@code PgInbox.of(...)} 재생성 방식은 attempt 파라미터가 없어 매번 1로 리셋되는 버그였다.
     */
    @Override
    public boolean transitPendingToInProgress(Long inboxId) {
        String orderId = idIndex.get(inboxId);
        if (orderId == null) {
            return false;
        }
        AtomicBoolean transitioned = new AtomicBoolean(false);
        store.computeIfPresent(orderId, (key, current) -> {
            if (current.getStatus() == PgInboxStatus.PENDING) {
                current.markInProgress(java.time.Instant.now());
                transitioned.set(true);
            }
            return current;
        });
        return transitioned.get();
    }

    /**
     * 보정 경로 — PENDING 우회, 바로 IN_PROGRESS.
     */
    @Override
    public Long transitDirectToInProgress(String orderId, long amount) {
        Long newId = idSequence.getAndIncrement();
        PgInbox inbox = PgInbox.createDirectInProgress(orderId, amount, java.time.Instant.now());
        store.put(orderId, inbox);
        idIndex.put(newId, orderId);
        return newId;
    }

    /**
     * 보정 경로 — PENDING + IN_PROGRESS 우회, 직접 terminal.
     */
    @Override
    public Long transitDirectToTerminal(String orderId, long amount, PgInboxStatus terminalStatus,
                                        String storedStatusResult, String reasonCode) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException(
                    "FakePgInboxRepository.transitDirectToTerminal: non-terminal status=" + terminalStatus);
        }
        Long newId = idSequence.getAndIncrement();
        java.time.Instant now = java.time.Instant.now();
        PgInbox inbox = PgInbox.of(orderId, terminalStatus, amount,
                storedStatusResult, reasonCode, now, now, null, null);
        store.put(orderId, inbox);
        idIndex.put(newId, orderId);
        return newId;
    }

    @Override
    public List<Long> findPendingZombieIds(int batchSize, long thresholdMs) {
        return List.of(); // Fake — 좀비 없음
    }

    @Override
    public List<Long> findInProgressZombieIds(int batchSize, long thresholdMs) {
        return List.of(); // Fake — 좀비 없음
    }

    /**
     * M4: IN_PROGRESS SKIP LOCKED 선점 — Fake 단순 구현.
     * Fake 에서는 동시성 없이 IN_PROGRESS row 조회와 동등하게 동작한다.
     */
    @Override
    public Optional<PgInbox> selectInProgressForUpdateSkipLocked(Long inboxId) {
        String orderId = idIndex.get(inboxId);
        if (orderId == null) {
            return Optional.empty();
        }
        PgInbox inbox = store.get(orderId);
        if (inbox == null || inbox.getStatus() != PgInboxStatus.IN_PROGRESS) {
            return Optional.empty();
        }
        return Optional.of(inbox);
    }

    /**
     * 시도횟수 SoT — relative increment.
     * store 의 PgInbox 를 attempt+1 로 재구성({@code ofWithId}, 나머지 필드 보존)해 교체한다.
     * 실 DB 의 {@code UPDATE attempt = attempt + 1} 과 동일 의미 — set-to-value 가 아니다.
     *
     * <p>진행 중 상태 가드 — 프로덕션({@code JpaPgInboxRepository.incrementAttempt})과 동일하게
     * IN_PROGRESS 행만 증가시킨다. 종결(APPROVED/FAILED/QUARANTINED) 행은 no-op — 여기서
     * 다르게 동작하면 상위 테스트가 거짓 GREEN 을 만든다. 반영 행 수(1 또는 0)를 그대로
     * 반환해 프로덕션({@code JpaPgInboxRepository.incrementAttempt})과 동일한 반환 계약을 지킨다.
     *
     * @return 1(정상 증가) 또는 0(가드 발동, 이미 종결)
     */
    @Override
    public int incrementAttempt(String orderId) {
        AtomicBoolean updated = new AtomicBoolean(false);
        store.computeIfPresent(orderId, (key, current) -> {
            if (current.getStatus() != PgInboxStatus.IN_PROGRESS) {
                return current; // 종결 행 — no-op
            }
            updated.set(true);
            return PgInbox.ofWithId(
                    current.getId(),
                    current.getOrderId(),
                    current.getStatus(),
                    current.getAmount(),
                    current.getStoredStatusResult(),
                    current.getReasonCode(),
                    current.getCreatedAt(),
                    java.time.Instant.now(),
                    current.getPaymentKey(),
                    current.getVendorType(),
                    current.getAttempt() + 1
            );
        });
        return updated.get() ? 1 : 0;
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
