package com.hyoguoo.paymentplatform.order.application.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossCancelInfo {

    private final String cancelReason;
    private final String paymentKey;
    private final String idempotencyKey;
}
