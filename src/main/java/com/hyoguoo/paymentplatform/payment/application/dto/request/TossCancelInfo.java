package com.hyoguoo.paymentplatform.payment.application.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossCancelInfo {

    private final String cancelReason;
    private final String paymentKey;
    private final String idempotencyKey;
}
