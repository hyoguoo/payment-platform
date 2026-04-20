package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * payment.events.stock-committed 토픽 발행 전용 고수준 port.
 * MessagePublisherPort를 래핑해 도메인 의도를 명시한다.
 * T1-10 구현 시 실 발행 어댑터로 교체 예정.
 */
public interface StockCommitEventPublisherPort {

    /**
     * 재고 커밋 이벤트를 발행한다.
     *
     * @param productId      재고 차감 대상 상품 ID
     * @param qty            차감 수량
     * @param idempotencyKey 멱등성 키 (주문 ID 등)
     */
    void publish(Long productId, int qty, String idempotencyKey);
}
