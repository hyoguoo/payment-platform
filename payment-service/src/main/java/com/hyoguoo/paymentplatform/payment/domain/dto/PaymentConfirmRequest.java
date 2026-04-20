package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;

public record PaymentConfirmRequest(
        String orderId,
        String paymentKey,
        BigDecimal amount,
        PaymentGatewayType gatewayType
) {

}
