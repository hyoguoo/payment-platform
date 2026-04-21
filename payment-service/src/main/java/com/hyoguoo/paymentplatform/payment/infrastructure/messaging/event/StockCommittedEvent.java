package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event;

import java.time.Instant;

/**
 * payment.events.stock-committed 토픽 payload.
 * Phase 3 product-service가 이 이벤트를 소비해 Redis 재고를 직접 SET한다.
 *
 * @param productId      재고 차감 대상 상품 ID (파티션 키로도 사용 — 동일 상품 이벤트 순서 보장)
 * @param qty            차감 수량
 * @param idempotencyKey 멱등성 키 (주문 ID 등 — 소비자 측 중복 처리 식별용)
 * @param occurredAt     이벤트 발생 시각
 */
public record StockCommittedEvent(
        Long productId,
        int qty,
        String idempotencyKey,
        Instant occurredAt
) {

}
