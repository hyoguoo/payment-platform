package com.hyoguoo.paymentplatform.product.application.port.out;

import java.time.Instant;

/**
 * 이벤트 중복 처리 방지 outbound 포트.
 * pg-service EventDedupeStore(markSeen)와 시그니처가 다름 — ADR-30 독립 복제 원칙.
 * product-service는 TTL 기반 recordIfAbsent 방식 채택.
 */
public interface EventDedupeStore {

    /**
     * eventUuid를 기록하고, 최초 기록이면 true를 반환한다.
     * 이미 존재하는 경우(중복) false를 반환한다.
     *
     * @param eventUuid  이벤트 식별자
     * @param expiresAt  만료 시각 (TTL)
     * @return 최초 기록이면 true, 중복이면 false
     */
    boolean recordIfAbsent(String eventUuid, Instant expiresAt);
}
