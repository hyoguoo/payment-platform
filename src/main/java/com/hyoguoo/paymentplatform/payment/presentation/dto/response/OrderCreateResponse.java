package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderCreateResponse {

    private final String orderId;
}
