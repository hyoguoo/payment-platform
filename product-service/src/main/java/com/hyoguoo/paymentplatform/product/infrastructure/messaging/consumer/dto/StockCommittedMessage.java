package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto;

import java.time.Instant;

/**
 * payment.events.stock-committed 토픽 페이로드 — product-service consumer 수신 DTO.
 * <p>
 * ADR-30(공통 jar 금지): payment-service StockCommittedEvent를 직접 참조하지 않고
 * product-service 독립 record로 복제한다.
 * <p>
 * 필드:
 * <ul>
 *   <li>productId — 재고 차감 대상 상품 ID</li>
 *   <li>qty — 확정 차감 수량</li>
 *   <li>idempotencyKey — 멱등성 키 (eventUUID로 사용)</li>
 *   <li>occurredAt — 이벤트 발생 시각</li>
 *   <li>orderId — 주문 ID (dedupe 메타데이터)</li>
 *   <li>expiresAt — dedupe TTL 만료 시각 (메시지에 없으면 consumer가 계산)</li>
 * </ul>
 */
public record StockCommittedMessage(
        Long productId,
        int qty,
        String idempotencyKey,
        Instant occurredAt,
        Long orderId,
        Instant expiresAt
) {

}
