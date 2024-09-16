package com.hyoguoo.paymentplatform.paymentgateway.application.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossCancelRequest {

    private final String cancelReason;
    private final String paymentKey;
}
