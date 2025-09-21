package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentHistoryResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentHistoryResponse {

    private final Long id;
    private final Long paymentEventId;
    private final String orderId;
    private final PaymentEventStatus previousStatus;
    private final PaymentEventStatus currentStatus;
    private final String reason;
    private final LocalDateTime changeStatusAt;

    public static PaymentHistoryResponse from(PaymentHistoryResult result) {
        return PaymentHistoryResponse.builder()
                .id(result.getId())
                .paymentEventId(result.getPaymentEventId())
                .orderId(result.getOrderId())
                .previousStatus(result.getPreviousStatus())
                .currentStatus(result.getCurrentStatus())
                .reason(result.getReason())
                .changeStatusAt(result.getChangeStatusAt())
                .build();
    }
}
