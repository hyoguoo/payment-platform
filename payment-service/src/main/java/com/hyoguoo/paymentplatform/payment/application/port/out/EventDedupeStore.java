package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * payment-service outbound 포트 — 메시지 레벨 eventUUID dedupe 계약.
 * ADR-04(2단 멱등성 키): 메시지 레벨 dedupe — 동일 eventUUID 재소비 차단.
 * ADR-30: pg-service의 EventDedupeStore와 독립 복제 — 공통 lib 금지.
 *
 * <p>구현체:
 * <ul>
 *   <li>FakeEventDedupeStore (test source) — in-memory ConcurrentHashSet</li>
 *   <li>실제 Redis 구현체는 Phase 2.d+ 후속 태스크에서 추가 예정</li>
 * </ul>
 *
 * <p>markSeen(eventUuid): 최초 호출 시 true(새 UUID), 이미 본 UUID이면 false(중복).
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
