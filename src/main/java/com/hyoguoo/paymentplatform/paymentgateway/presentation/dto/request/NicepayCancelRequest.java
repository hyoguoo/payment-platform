package com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayCancelRequest {

    private final String tid;
    private final String reason;
    private final String orderId;
}
