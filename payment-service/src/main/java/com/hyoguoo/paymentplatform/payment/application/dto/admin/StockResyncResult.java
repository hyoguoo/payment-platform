package com.hyoguoo.paymentplatform.payment.application.dto.admin;

/**
 * 재고 캐시 재동기화 결과.
 *
 * @param quantity      캐시에 덮어쓴 수량(product RDB 값)
 * @param overlapStatus 덮어쓰기 전후 진행 중 선차감 건수 비교 결과
 */
public record StockResyncResult(int quantity, StockResyncOverlapStatus overlapStatus) {
}
