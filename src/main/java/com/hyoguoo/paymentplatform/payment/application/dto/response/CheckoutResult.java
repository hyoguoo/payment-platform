package com.hyoguoo.paymentplatform.payment.application.dto.response;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutResult {

    private final String orderId;
    private final BigDecimal totalAmount;
}
