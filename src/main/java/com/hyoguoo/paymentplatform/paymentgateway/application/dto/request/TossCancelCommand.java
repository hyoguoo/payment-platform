package com.hyoguoo.paymentplatform.paymentgateway.application.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossCancelCommand {

    private final String cancelReason;
    private final String paymentKey;
}
