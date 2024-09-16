package com.hyoguoo.paymentplatform.payment.application.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutResult {

    private final String orderId;
}
