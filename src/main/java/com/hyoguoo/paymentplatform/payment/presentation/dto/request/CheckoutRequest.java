package com.hyoguoo.paymentplatform.payment.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutRequest {

    private final Long userId;
    private final BigDecimal amount;
    private final Long productId;
    private final Integer quantity;
}
