package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossCancelRequest {

    private final String paymentKey;
    private final String cancelReason;
    private final String idempotencyKey;
}
