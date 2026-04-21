package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventDedupeStore Fake — in-memory 구현체 (payment-service 전용).
 * ADR-04(2단 멱등성): 메시지 레벨 eventUUID dedupe 테스트용.
 * ADR-30: pg-service의 FakeEventDedupeStore와 독립 복제 — 공통 lib 금지.
 *
 * <p>Thread-safe: ConcurrentHashMap.newKeySet() 기반.
 */
public class FakeEventDedupeStore implements EventDedupeStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markSeen(String eventUuid) {
        return seen.add(eventUuid);
    }

    // --- 검증 헬퍼 ---

    public int size() {
        return seen.size();
    }

    public boolean contains(String eventUuid) {
        return seen.contains(eventUuid);
    }

    // --- 초기화 ---

    public void reset() {
        seen.clear();
    }
}
