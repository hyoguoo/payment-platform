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
     * TTL 만료된 엔트리는 덮어쓰고 true를 반환한다.
     *
     * @param eventUUID  이벤트 식별자
     * @param expiresAt  만료 시각 (TTL)
     * @return 최초 기록(또는 만료 후 재기록)이면 true, 유효한 중복이면 false
     */
    boolean recordIfAbsent(String eventUUID, Instant expiresAt);

    /**
     * eventUUID가 유효하게(TTL 미만료) 존재하는지 확인한다.
     * 만료된 엔트리는 존재하지 않는 것으로 간주한다.
     *
     * @param eventUUID 이벤트 식별자
     * @return 유효한 중복이면 true, 없거나 만료됐으면 false
     */
    boolean existsValid(String eventUUID);
}
