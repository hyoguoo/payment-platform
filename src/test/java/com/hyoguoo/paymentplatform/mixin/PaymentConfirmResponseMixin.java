package com.hyoguoo.paymentplatform.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@SuppressWarnings("unused")
public class PaymentConfirmResponseMixin {

    @JsonCreator
    public PaymentConfirmResponseMixin(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("amount") BigDecimal amount
    ) {
    }
}
