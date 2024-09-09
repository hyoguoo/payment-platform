package com.hyoguoo.paymentplatform.payment.application.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class TossCancelRequest {

    private final String cancelReason;
    private final String paymentKey;
}
