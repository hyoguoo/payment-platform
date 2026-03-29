package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentStatusResult(
        String paymentKey,
        String orderId,
        PaymentStatus status,
        BigDecimal amount,
        LocalDateTime approvedAt,
        PaymentFailureInfo failure
) {

}
