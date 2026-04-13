package com.hyoguoo.paymentplatform.paymentgateway.application.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NicepayCancelCommand {

    private final String tid;
    private final String reason;
    private final String orderId;
}
