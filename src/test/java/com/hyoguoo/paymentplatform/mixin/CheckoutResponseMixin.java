package com.hyoguoo.paymentplatform.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@SuppressWarnings("unused")
public class CheckoutResponseMixin {

    @JsonCreator
    public CheckoutResponseMixin(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("totalAmount") BigDecimal totalAmount
    ) {
    }
}
