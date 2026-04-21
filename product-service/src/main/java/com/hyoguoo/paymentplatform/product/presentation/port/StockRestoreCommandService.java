package com.hyoguoo.paymentplatform.product.presentation.port;

/**
 * 재고 복원 인바운드 포트 (presentation 계층 진입점).
 * stock.events.restore 이벤트 소비 → 재고 원복 — T3-05에서 구현 완성.
 */
public interface StockRestoreCommandService {

    /**
     * orderId에 해당하는 주문의 재고를 복원한다.
     *
     * @param orderId   주문 식별자
     * @param eventUuid 이벤트 UUID (dedupe용)
     */
    void restore(String orderId, String eventUuid);
}
