package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * stock.events.restore 토픽 payload.
 * FAILED 결제 보상 — 예약된 재고를 복원하기 위한 이벤트.
 *
 * <p>ADR-16 (UUID dedupe): eventUUID는 orderId 기반 결정론적 UUID v3 → 동일 주문 재발행 시 동일 값.
 * 소비자는 이 UUID로 중복을 차단한다.
 *
 * @param eventUUID  멱등 키 (결정론적 UUID v3, orderId 기반)
 * @param orderId    주문 ID
 * @param productId  복원 대상 상품 ID (파티션 키)
 * @param qty        복원 수량
 * @param occurredAt 이벤트 발생 시각
 */
public record StockRestoreEvent(
        UUID eventUUID,
        String orderId,
        Long productId,
        int qty,
        Instant occurredAt
) {

}
