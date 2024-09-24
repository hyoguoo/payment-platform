package com.hyoguoo.paymentplatform.paymentgateway.domain.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentFailure {

    private final String code;
    private final String message;

    @JsonCreator
    public TossPaymentFailure(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message
    ) {
        this.code = code;
        this.message = message;
    }
}
