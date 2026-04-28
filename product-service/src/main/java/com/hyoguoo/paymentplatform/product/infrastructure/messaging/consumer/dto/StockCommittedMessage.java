package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto;

import java.time.Instant;

/**
 * payment.events.stock-committed 토픽 페이로드 — product-service consumer 수신 DTO.
 * <p>
 * 공통 jar 금지 정책에 따라 payment-service StockCommittedEvent 를 직접 참조하지 않고
 * product-service 자체 record 로 복제 보유한다.
 * <p>
 * orderId 는 producer(payment-service StockCommittedEvent) 와 타입을 맞춰 String 으로 둔다 —
 * 그래야 형 변환 충돌이 없다. expiresAt 은 producer 가 직접 채워 전송하며, consumer 의 fallback 은
 * 구버전 페이로드와의 하위 호환 용도로만 남긴다.
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
