package com.hyoguoo.paymentplatform.payment.application.dto;

import java.util.UUID;

/**
 * stock.events.restore 보상 이벤트 payload.
 * ADR-16(UUID dedupe): eventUUID는 orderId 기반 결정론적 생성 → 동일 orderId 재호출 시 동일 UUID.
 *
 * @param eventUUID  멱등성 키 (ADR-16: UUID v3 결정론적, orderId 기반)
 * @param orderId    주문 ID
 * @param productId  복원 대상 상품 ID
 * @param qty        복원 수량
 */
public record StockRestoreEventPayload(
        UUID eventUUID,
        String orderId,
        Long productId,
        int qty
) {

}
