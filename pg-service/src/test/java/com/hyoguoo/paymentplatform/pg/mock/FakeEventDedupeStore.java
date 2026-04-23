package com.hyoguoo.paymentplatform.pg.mock;

import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EventDedupeStore Fake — in-memory 구현체.
 * ADR-04(2단 멱등성): 메시지 레벨 eventUUID dedupe 테스트용.
 *
 * <p>Thread-safe: ConcurrentHashMap.newKeySet() 기반.
 * 실제 Redis 구현체는 Phase 2.b+ 후속 태스크에서 추가 예정.
 */
public class FakeEventDedupeStore implements EventDedupeStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * eventUuid를 최초 처리하는 경우 seen에 등록하고 true를 반환한다.
     * 이미 seen에 존재하면 false를 반환한다.
     *
     * <p>ConcurrentHashMap.newKeySet().add() 는 thread-safe한 원자 연산이다.
     */
    @Override
    public boolean markSeen(String eventUuid) {
        return seen.add(eventUuid);
    }

    @Override
    public void remove(String eventUuid) {
        seen.remove(eventUuid);
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
