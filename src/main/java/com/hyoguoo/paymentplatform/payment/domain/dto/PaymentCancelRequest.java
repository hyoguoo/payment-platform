package com.hyoguoo.paymentplatform.payment.domain.dto;

import java.math.BigDecimal;

public record PaymentCancelRequest(
        String paymentKey,
        String cancelReason,
        BigDecimal amount
) {

}
