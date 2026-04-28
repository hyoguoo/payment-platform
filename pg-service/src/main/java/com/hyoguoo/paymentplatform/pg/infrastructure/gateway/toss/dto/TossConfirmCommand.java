package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto;

import java.math.BigDecimal;

/**
 * Toss Payments /v1/payments/confirm 요청 바디 DTO.
 *
 * @param paymentKey 결제 고유 키
 * @param orderId    주문 ID
 * @param amount     결제 금액 (원 단위)
 */
public record TossConfirmCommand(String paymentKey, String orderId, BigDecimal amount) {
}
