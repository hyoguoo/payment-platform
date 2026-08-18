package com.hyoguoo.paymentplatform.payment.application.util;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;

/**
 * 확정 실패·격리 진입·관리자 종결·미회수 회수 판정 네 호출부가 공유하는 상품 단위 되돌리기.
 *
 * <p>주문에 속한 상품마다 선차감 흔적이 있을 때만 캐시를 되돌리고, 그 결과와 무관하게
 * 기록을 되돌림으로 닫는다. 캐시가 이미 처리됨을 돌려줘도(다른 경로가 먼저 되돌린 경우)
 * 기록은 닫는다 — 닫지 않으면 회수 작업이 매 주기 같은 기록을 집었다가 못 닫는 유령이 된다.
 */
public final class StockHoldReverter {

    private StockHoldReverter() {
        // 유틸리티 클래스 — 인스턴스화 금지
    }

    public static void revertEachProductHold(
            PaymentEvent paymentEvent,
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository) {
        String orderId = paymentEvent.getOrderId();
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            stockHoldRecordRepository.findSnapshot(orderId, order)
                    .ifPresent(snapshot -> revertProductHold(
                            orderId, order, snapshot.cycleToken(), stockCachePort, stockHoldRecordRepository));
        }
    }

    /**
     * 상품 하나에 대한 되돌리기 — 이미 조회해 둔 사이클 식별 값을 그대로 받는다.
     *
     * <p>{@link #revertEachProductHold}는 {@code PaymentEvent}의 상품 목록을 반복하며 매 상품마다
     * {@code findSnapshot}으로 사이클 식별 값을 새로 조회하지만, 미회수 회수 판정(배치 스캔으로
     * 후보를 이미 사이클 식별 값과 함께 들고 있는 경우)처럼 그 조회가 이미 끝난 호출부를 위해
     * 공개 메서드로 둔다.
     */
    public static void revertProductHold(
            String orderId,
            PaymentOrder order,
            String cycleToken,
            StockCachePort stockCachePort,
            StockHoldRecordRepository stockHoldRecordRepository) {
        stockCachePort.compensateIfDecremented(orderId, order);
        stockHoldRecordRepository.closeAsReverted(orderId, order, cycleToken);
    }
}
