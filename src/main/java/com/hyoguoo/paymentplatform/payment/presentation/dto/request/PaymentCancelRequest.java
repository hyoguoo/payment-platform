package com.hyoguoo.paymentplatform.payment.presentation.dto.request;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentCancelRequest {

    private final String orderId;
    private final String cancelReason;
}
