package com.hyoguoo.paymentplatform.payment.domain.dto;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;

public record PaymentCancelRequest(
        String paymentKey,
        String cancelReason,
        BigDecimal amount,
        PaymentGatewayType gatewayType
) {

}
