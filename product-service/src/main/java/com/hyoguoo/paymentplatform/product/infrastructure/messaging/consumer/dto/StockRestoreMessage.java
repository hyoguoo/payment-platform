package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * stock.events.restore 토픽 페이로드 — product-service consumer 수신 DTO.
 * <p>
 * ADR-30(공통 jar 금지): payment-service StockRestoreEventPayload를 직접 참조하지 않고
 * product-service 독립 record로 복제한다.
 * <p>
 * 필드:
 * <ul>
 *   <li>orderId — 주문 ID</li>
 *   <li>eventUUID — 이벤트 UUID (dedupe 키)</li>
 *   <li>productId — 복원 대상 상품 ID</li>
 *   <li>qty — 복원 수량</li>
 * </ul>
 */
public record StockRestoreMessage(
        String orderId,
        @JsonProperty("eventUUID") String eventUUID,
        long productId,
        int qty
) {

}
