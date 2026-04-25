package com.hyoguoo.paymentplatform.payment.application.port.out;

import java.time.Duration;

/**
 * payment-service outbound 포트 — 메시지 레벨 eventUUID dedupe 계약.
 * ADR-04(2단 멱등성 키): 메시지 레벨 dedupe — 동일 eventUUID 재소비 차단.
 * ADR-30: pg-service의 EventDedupeStore와 독립 복제 — 공통 lib 금지.
 *
 * <p>구현체:
 * <ul>
 *   <li>FakeEventDedupeStore (test source) — in-memory ConcurrentHashMap + TTL Clock</li>
 *   <li>EventDedupeStoreRedisAdapter (infrastructure) — Redis SET NX/XX EX</li>
 * </ul>
 *
 * <p>T-C3 two-phase lease 패턴:
 * <ol>
 *   <li>{@link #markWithLease(String, Duration)} — shortTtl(5m)로 처리 권한 예약</li>
 *   <li>processMessage 성공 후 {@link #extendLease(String, Duration)} — longTtl(P8D)로 연장</li>
 *   <li>실패 시 {@link #remove(String)} — 재처리 가능 상태로 복원. false 반환 시 DLQ 전송</li>
 * </ol>
 *
 */
public interface EventDedupeStore {

    /**
     * shortTtl 동안 eventUuid에 대한 처리 권한을 예약(lease)한다.
     * 이미 처리 중인(또는 완료된) UUID이면 false를 반환한다.
     *
     * <p>Redis 구현: SET NX EX shortTtl.
     * Fake 구현: ConcurrentHashMap + 만료 시각 추적.
     *
     * @param eventUuid 이벤트 고유 식별자
     * @param shortTtl  초기 lease TTL (예: 5분)
     * @return true — 처리 권한 획득(새 UUID 또는 만료된 UUID), false — 이미 처리 중
     */
    boolean markWithLease(String eventUuid, Duration shortTtl);

    /**
     * processMessage 성공 후 dedupe 키의 TTL을 longTtl로 연장한다.
     * 키가 존재하지 않으면(예: Redis flap으로 만료) false를 반환한다.
     *
     * <p>Redis 구현: SET XX EX longTtl (존재 시에만 갱신).
     * Fake 구현: 키 존재 확인 후 만료 시각 갱신.
     *
     * @param eventUuid 이벤트 고유 식별자
     * @param longTtl   연장할 TTL (예: P8D)
     * @return true — 연장 성공, false — 키 없음(no-op)
     */
    boolean extendLease(String eventUuid, Duration longTtl);

    /**
     * dedupe 기록을 제거한다. processMessage 실패 시 재컨슘을 허용하기 위해 호출한다.
     * 삭제 성공 여부를 반환한다 — false이면 Redis flap 등으로 제거 실패를 의미한다.
     *
     * <p>Redis 구현: DEL key → boolean(삭제 건수 > 0).
     * Fake 구현: Map.remove 결과.
     *
     * @param eventUuid 제거할 이벤트 고유 식별자
     * @return true — 삭제 성공, false — 키 없음 또는 Redis 오류
     */
    boolean remove(String eventUuid);

}
