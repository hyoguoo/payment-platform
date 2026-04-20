package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonDeserialize(builder = CheckoutResult.CheckoutResultBuilder.class)
public class CheckoutResult {

    private final String orderId;
    private final BigDecimal totalAmount;
    private final boolean isDuplicate;

    @JsonPOJOBuilder(withPrefix = "")
    public static final class CheckoutResultBuilder {
        // Lombok generates the builder body; Jackson uses this class via @JsonDeserialize
    }
}
