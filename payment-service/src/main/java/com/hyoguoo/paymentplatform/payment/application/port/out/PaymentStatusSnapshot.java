package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.Instant;

/**
 * 폴링 조회 시점의 결제 이벤트 상태와 승인 시각.
 *
 * @param orderId    주문 ID
 * @param status     조회 시점 결제 이벤트 상태
 * @param approvedAt 승인 시각 (미승인 상태에서는 null)
 */
public record PaymentStatusSnapshot(String orderId, PaymentEventStatus status, Instant approvedAt) {

}
