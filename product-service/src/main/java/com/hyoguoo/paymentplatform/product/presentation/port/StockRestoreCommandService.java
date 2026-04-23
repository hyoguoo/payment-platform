package com.hyoguoo.paymentplatform.product.presentation.port;

/**
 * 재고 복원 인바운드 포트 (presentation 계층 진입점).
 * stock.events.restore 이벤트 소비 → 재고 원복 — T3-05에서 구현 완성.
 */
public interface StockRestoreCommandService {

    /**
     * 지정 상품의 재고를 qty만큼 복원한다.
     *
     * @param orderId   주문 식별자
     * @param eventUUID 이벤트 UUID (dedupe용)
     * @param productId 복원 대상 상품 ID
     * @param qty       복원 수량
     */
    void restore(String orderId, String eventUUID, long productId, int qty);
}
