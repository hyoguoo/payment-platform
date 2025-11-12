package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentConfirmResult(
        PaymentConfirmResultStatus status,
        String paymentKey,
        String orderId,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PaymentFailureInfo failure
) {

    public boolean isSuccess() {
        return status == PaymentConfirmResultStatus.SUCCESS;
    }

    public boolean isRetryable() {
        return status == PaymentConfirmResultStatus.RETRYABLE_FAILURE;
    }
}
