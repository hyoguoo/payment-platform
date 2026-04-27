package com.hyoguoo.paymentplatform.pg.application.port.out;

/**
 * pg-service outbound 포트 — 메시지 레벨 eventUUID dedupe 계약.
 * 2단 멱등성 키 중 메시지 레벨 — 동일 eventUUID 재소비를 차단한다.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code EventDedupeStoreRedisAdapter} — Redis SETNX 기반 운영 구현</li>
 *   <li>{@code FakeEventDedupeStore} (test source) — in-memory ConcurrentHashSet</li>
 * </ul>
 *
 * <p>markSeen(eventUuid): 최초 호출 시 true(새 UUID), 이미 본 UUID 이면 false(중복).
 */
public interface EventDedupeStore {

    /**
     * eventUuid를 최초로 처리하는 경우 true를 반환하고 seen으로 등록한다.
     * 이미 처리된 eventUuid이면 false를 반환한다 (no-op 신호).
     *
     * @param eventUuid 이벤트 고유 식별자
     * @return true — 새 UUID (처리 진행), false — 중복 UUID (no-op)
     */
    boolean markSeen(String eventUuid);

    /**
     * markSeen으로 기록된 eventUuid를 제거한다. 처리 TX가 롤백된 경우 dedupe 기록을 되돌려
     * 재컨슘 시 정상 처리되도록 한다. 존재하지 않는 UUID 호출은 no-op.
     *
     * @param eventUuid 제거할 이벤트 고유 식별자
     */
    void remove(String eventUuid);
}
