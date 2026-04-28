package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NicepayPaymentApiFailResponse(String resultCode, String resultMsg) {

    @JsonCreator
    public NicepayPaymentApiFailResponse(
            @JsonProperty("resultCode") String resultCode,
            @JsonProperty("resultMsg") String resultMsg
    ) {
        this.resultCode = resultCode;
        this.resultMsg = resultMsg;
    }
}
