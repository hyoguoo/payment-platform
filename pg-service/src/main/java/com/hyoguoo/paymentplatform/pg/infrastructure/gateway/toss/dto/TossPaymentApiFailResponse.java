package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Toss Payments 실패 응답. errorCode/message 2개 필드만 유지.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentApiFailResponse(String code, String message) {

    @JsonCreator
    public TossPaymentApiFailResponse(
            @JsonProperty("code") String code,
            @JsonProperty("message") String message
    ) {
        this.code = code;
        this.message = message;
    }
}
