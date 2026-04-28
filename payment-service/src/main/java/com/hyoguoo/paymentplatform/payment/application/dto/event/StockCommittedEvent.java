package com.hyoguoo.paymentplatform.payment.application.dto.event;

import java.time.Instant;

/**
 * payment.events.stock-committed 토픽 payload.
 * Phase 3 product-service가 이 이벤트를 소비해 Redis 재고를 직접 SET한다.
 *
 * <p>product-service {@code StockCommittedMessage} 와 필드 갯수·순서·타입을 통일한다.
 * producer 가 {@code orderId}(String) + {@code expiresAt}(Instant) 를 명시 전달해
 * consumer 측이 null fallback 에 의존하지 않도록 한다. 공유 JAR 없이 독립 복제이므로
 * {@code StockCommittedSchemaParityTest} 가 양쪽 동기화를 강제한다.
 *
 * @param productId      재고 차감 대상 상품 ID (파티션 키로도 사용 — 동일 상품 이벤트 순서 보장)
 * @param qty            차감 수량
 * @param idempotencyKey 멱등성 키 — (orderId, productId) 시드 기반 결정론적 UUID v3
 * @param occurredAt     이벤트 발생 시각
 * @param orderId        주문 ID (dedupe 메타데이터, String 통일)
 * @param expiresAt      dedupe TTL 만료 시각 (Kafka retention + 버퍼)
 */
public record StockCommittedEvent(
        Long productId,
        int qty,
        String idempotencyKey,
        Instant occurredAt,
        String orderId,
        Instant expiresAt
) {

}
