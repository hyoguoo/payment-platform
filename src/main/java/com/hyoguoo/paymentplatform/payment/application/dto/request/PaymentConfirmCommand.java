package com.hyoguoo.paymentplatform.payment.application.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentConfirmCommand {

    private final Long userId;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
