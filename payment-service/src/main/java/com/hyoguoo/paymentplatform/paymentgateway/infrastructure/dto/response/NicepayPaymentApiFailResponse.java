package com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayPaymentApiFailResponse {

    private final String resultCode;
    private final String resultMsg;

    @JsonCreator
    public NicepayPaymentApiFailResponse(
            @JsonProperty("resultCode") String resultCode,
            @JsonProperty("resultMsg") String resultMsg
    ) {
        this.resultCode = resultCode;
        this.resultMsg = resultMsg;
    }
}
