package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 회수 판정 대상 후보 — 잡음(NOISE) 상태로 남은 선차감 기록 한 행의 스냅샷.
 *
 * <p>{@link StockHoldRecordRepository#findNoiseCandidates}가 돌려주는 형태다. 되돌리기 호출에
 * 필요한 최소 정보(상품·수량)와, 기록을 닫을 때 자기 사이클만 닫도록 하는 사이클 식별 값을 함께
 * 들고 있어, 이 값을 다시 조회하지 않고도 바로 되돌리기·닫기를 수행할 수 있다.
 *
 * @param orderId    결제 주문 ID
 * @param productId  상품 ID
 * @param quantity   차감 수량
 * @param cycleToken 조회 시점 기록이 속한 사이클을 식별하는 값
 */
public record StockHoldRecordCandidate(String orderId, Long productId, Integer quantity, String cycleToken) {

}
