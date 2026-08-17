package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;

/**
 * 선차감 기록 한 행의 조회 시점 상태와 사이클 식별 값.
 *
 * <p>상태만으로는 되돌리기 후 닫기를 걸 수 없다 — {@link StockHoldRecordRepository#closeAsReverted}가
 * 사이클 식별 값 일치를 조건으로 삼으므로, 조회 시점의 값을 함께 들고 있어야 그 뒤의 닫기 호출이
 * 자기 사이클만 닫는다는 것을 보장할 수 있다.
 *
 * @param status     조회 시점 상태 (잡음 / 되돌림 / 확정)
 * @param cycleToken 조회 시점 기록이 속한 사이클을 식별하는 값
 */
public record StockHoldRecordSnapshot(StockHoldRecordStatus status, String cycleToken) {

}
