package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto;

import java.time.Instant;

/**
 * payment.events.stock-committed 토픽 페이로드 — product-service consumer 수신 DTO.
 * <p>
 * ADR-30(공통 jar 금지): payment-service StockCommittedEvent를 직접 참조하지 않고
 * product-service 독립 record로 복제한다.
 * <p>
 * K3: orderId 타입을 Long에서 String으로 통일.
 * producer(payment-service StockCommittedEvent)의 orderId가 String이므로
 * consumer도 동일 타입으로 수신해야 형 변환 충돌이 없다.
 * expiresAt은 producer가 직접 채워 전송 — consumer fallback은 하위 호환용으로 유지.
 * <p>
 * 필드:
 * <ul>
 *   <li>productId — 재고 차감 대상 상품 ID</li>
 *   <li>qty — 확정 차감 수량</li>
 *   <li>idempotencyKey — 멱등성 키 (eventUUID로 사용)</li>
 *   <li>occurredAt — 이벤트 발생 시각</li>
 *   <li>orderId — 주문 ID (dedupe 메타데이터, String 통일)</li>
 *   <li>expiresAt — dedupe TTL 만료 시각 (producer가 채우고, null이면 consumer가 fallback 계산)</li>
 * </ul>
 */
public record StockCommittedMessage(
        Long productId,
        int qty,
        String idempotencyKey,
        Instant occurredAt,
        String orderId,
        Instant expiresAt
) {

}
