package com.hyoguoo.paymentplatform.payment.application.event;

/**
 * stock.events.restore 발행 요청 Spring ApplicationEvent.
 * ADR-04: TX 내부에서 발행 — 실제 Kafka 발행은 AFTER_COMMIT 리스너가 수행한다.
 *
 * @param eventUUID 멱등성 키 (ADR-16 결정론적 UUID — orderId+productId 기반)
 * @param orderId   주문 ID
 * @param productId 복원 대상 상품 ID
 * @param quantity  복원 수량
 */
public record StockRestoreRequestedEvent(
        String eventUUID,
        String orderId,
        Long productId,
        int quantity
) {

}
