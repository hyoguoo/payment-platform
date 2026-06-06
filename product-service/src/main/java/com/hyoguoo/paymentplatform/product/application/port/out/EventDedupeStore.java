package com.hyoguoo.paymentplatform.product.application.port.out;

import java.time.Instant;

/**
 * 이벤트 중복 처리 방지 outbound 포트.
 * pg-service EventDedupeStore(markSeen) 와 시그니처가 다르다 — 공통 jar 금지 정책에 따라 독립 정의.
 * product-service 는 TTL 기반 recordIfAbsent 방식을 채택한다.
 */
public interface EventDedupeStore {

    /**
     * eventUUID를 기록하고, 최초 기록이면 true를 반환한다.
     * 이미 존재하는 경우(중복) false를 반환한다.
     * TTL 만료된 엔트리(expires_at &lt; now)는 삭제 후 재삽입하여 true를 반환한다.
     *
     * <p>D1 — now 는 호출자(컨슈머 진입점)가 단일 시각으로 산출해 주입한다.
     * 포트 구현체는 내부에서 Clock.instant() 를 호출하지 않는다.
     *
     * @param eventUUID  이벤트 식별자
     * @param now        현재 시각 — 만료 경계 판정 기준 (호출자 주입)
     * @param expiresAt  만료 시각 (TTL)
     * @return 최초 기록(또는 만료 후 재기록)이면 true, 유효한 중복이면 false
     */
    boolean recordIfAbsent(String eventUUID, Instant now, Instant expiresAt);

    /**
     * 만료된 dedupe 행을 일괄 삭제한다.
     * expires_at &lt; now 조건의 idempotent batch DELETE.
     * 동시 실행 시 이미 삭제된 행은 0 row affected — 무해.
     *
     * @param now       현재 시각
     * @param batchSize 최대 삭제 건수
     * @return 실제 삭제된 행 수
     */
    int deleteExpired(Instant now, int batchSize);
}
