package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossConfirmRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final String idempotencyKey;
}
