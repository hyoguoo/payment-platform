package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentConfirmResponse {

    private final String orderId;
    private final BigDecimal amount;
}
