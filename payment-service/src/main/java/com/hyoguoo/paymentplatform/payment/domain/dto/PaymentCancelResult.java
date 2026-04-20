package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentCancelResult(
        PaymentCancelResultStatus status,
        String paymentKey,
        LocalDateTime canceledAt,
        BigDecimal canceledAmount,
        PaymentFailureInfo failure
) {

    public boolean isSuccess() {
        return status == PaymentCancelResultStatus.SUCCESS;
    }
}
