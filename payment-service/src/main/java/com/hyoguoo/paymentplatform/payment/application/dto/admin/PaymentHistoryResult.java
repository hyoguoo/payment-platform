package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentHistoryResult {

    private final Long id;
    private final Long paymentEventId;
    private final String orderId;
    private final PaymentEventStatus previousStatus;
    private final PaymentEventStatus currentStatus;
    private final String reason;
    private final LocalDateTime changeStatusAt;

    public static PaymentHistoryResult from(PaymentHistory paymentHistory) {
        return PaymentHistoryResult.builder()
                .id(paymentHistory.getId())
                .paymentEventId(paymentHistory.getPaymentEventId())
                .orderId(paymentHistory.getOrderId())
                .previousStatus(paymentHistory.getPreviousStatus())
                .currentStatus(paymentHistory.getCurrentStatus())
                .reason(paymentHistory.getReason())
                .changeStatusAt(paymentHistory.getChangeStatusAt())
                .build();
    }
}
