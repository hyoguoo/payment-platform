package com.hyoguoo.paymentplatform.product.mock;

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventDedupeStore Fake — in-memory TTL 시뮬레이션 구현체 (product-service 테스트 전용).
 * <p>
 * UUID dedupe + Redis TTL 보상 로직 테스트 지원.
 * 공통 jar 금지 정책에 따라 payment-service / pg-service 의 FakeEventDedupeStore 와 서비스별로 따로 둔다.
 * <p>
 * TTL 만료 시뮬레이션:
 * - recordIfAbsent 호출 시 기존 엔트리의 expiresAt가 현재 시각(clock) 이전이면 만료로 간주,
 *   덮어쓰기 후 true를 반환한다.
 * - 테스트 결정성을 위해 {@link Clock.fixed} 주입을 허용한다.
 * <p>
 * Thread-safe: ConcurrentHashMap.compute 원자적 갱신.
 */
public class FakeEventDedupeStore implements EventDedupeStore {

    private final ConcurrentHashMap<String, Instant> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public FakeEventDedupeStore() {
        this.clock = Clock.systemUTC();
    }

    public FakeEventDedupeStore(Clock clock) {
        this.clock = clock;
    }

    /**
     * eventUUID를 기록하고, 최초 기록이면 true를 반환한다.
     * 이미 존재하지만 만료된 경우(expiresAt &lt; now) 덮어쓰기 후 true를 반환한다.
     * 유효한 중복이면 false를 반환한다.
     */
    @Override
    public boolean recordIfAbsent(String eventUUID, Instant expiresAt) {
        boolean[] recorded = {false};
        store.compute(eventUUID, (key, existing) -> {
            if (existing == null || existing.isBefore(Instant.now(clock))) {
                recorded[0] = true;
                return expiresAt;
            }
            recorded[0] = false;
            return existing;
        });
        return recorded[0];
    }

    /**
     * eventUUID가 유효하게(TTL 미만료) 존재하는지 확인한다.
     * 만료된 엔트리는 존재하지 않는 것으로 간주한다.
     */
    @Override
    public boolean existsValid(String eventUUID) {
        Instant expiry = store.get(eventUUID);
        if (expiry == null) {
            return false;
        }
        return !expiry.isBefore(Instant.now(clock));
    }

    // --- 검증 헬퍼 ---

    public boolean contains(String eventUUID) {
        return store.containsKey(eventUUID);
    }

    public int size() {
        return store.size();
    }

    public void reset() {
        store.clear();
    }
}
