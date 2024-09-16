package com.hyoguoo.paymentplatform.payment.application.dto.request;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@Builder
@RequiredArgsConstructor
public class OrderCancelInfo {

    private final String orderId;
    private final String cancelReason;
}
