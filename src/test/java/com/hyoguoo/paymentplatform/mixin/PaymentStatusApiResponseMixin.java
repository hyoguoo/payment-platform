package com.hyoguoo.paymentplatform.mixin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusResponse;
import java.time.LocalDateTime;

@SuppressWarnings("unused")
public class PaymentStatusApiResponseMixin {

    @JsonCreator
    public PaymentStatusApiResponseMixin(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("status") PaymentStatusResponse status,
            @JsonProperty("approvedAt") LocalDateTime approvedAt
    ) {
    }
}
