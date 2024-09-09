package com.hyoguoo.paymentplatform.order.presentation.dto.request;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCancelRequest {

    private final String orderId;
    private final String cancelReason;
}