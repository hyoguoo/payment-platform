package com.hyoguoo.paymentplatform.payment.presentation.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossCancelClientRequest {

    private final String paymentKey;
    private final String cancelReason;
    private final String idempotencyKey;
}
