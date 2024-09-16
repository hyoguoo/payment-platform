package com.hyoguoo.paymentplatform.payment.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentConfirmRequest {

    private final Long userId;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
