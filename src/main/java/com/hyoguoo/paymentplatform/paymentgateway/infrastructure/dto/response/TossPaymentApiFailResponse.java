package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentApiFailResponse {

    private final String code;
    private final String message;

    @JsonCreator
    public TossPaymentApiFailResponse(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message
    ) {
        this.code = code;
        this.message = message;
    }
}
