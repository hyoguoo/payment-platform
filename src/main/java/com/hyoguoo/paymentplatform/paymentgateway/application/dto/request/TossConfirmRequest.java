package com.hyoguoo.paymentplatform.paymentgateway.application.dto.request;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossConfirmRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
