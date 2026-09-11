package com.hyoguoo.paymentplatform.payment.application.dto.admin;

/**
 * 재고 캐시 재동기화 전후 진행 중 선차감 겹침 판정 — 있음 / 없음 / 미상 세 값.
 *
 * <p>덮어쓰기 전후로 그 상품의 진행 중(잡음 상태) 선차감 건수를 비교해 판정한다. 사후 재확인
 * 자체가 실패하면 판정 불가를 "없음"으로 접지 않고 미상으로 남긴다 — 없는 안전을 보고하지
 * 않기 위함이다.
 */
public enum StockResyncOverlapStatus {
    OVERLAPPED,
    CLEAR,
    UNKNOWN
}
