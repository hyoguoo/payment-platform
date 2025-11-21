package com.hyoguoo.paymentplatform.payment.domain.dto;

import java.math.BigDecimal;

public record PaymentConfirmRequest(
        String orderId,
        String paymentKey,
        BigDecimal amount
) {

}
