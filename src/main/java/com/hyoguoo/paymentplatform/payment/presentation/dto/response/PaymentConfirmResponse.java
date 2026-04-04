package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentConfirmResponse {

    private final String orderId;
    private final BigDecimal amount;
    private final String message;
}
