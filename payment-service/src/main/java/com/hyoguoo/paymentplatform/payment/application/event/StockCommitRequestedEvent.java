package com.hyoguoo.paymentplatform.payment.application.event;

/**
 * stock.events.commit 발행 요청 Spring ApplicationEvent.
 * ADR-04: TX 내부에서 발행 — 실제 Kafka 발행은 AFTER_COMMIT 리스너가 수행한다.
 *
 * @param eventUUID      멱등성 키 (ADR-16 결정론적 UUID)
 * @param orderId        주문 ID
 * @param productId      재고 차감 대상 상품 ID
 * @param quantity       차감 수량
 * @param idempotencyKey 멱등성 키 (주문 ID 등 — 소비자 측 중복 처리 식별용)
 */
public record StockCommitRequestedEvent(
        String eventUUID,
        String orderId,
        Long productId,
        int quantity,
        String idempotencyKey
) {

}
