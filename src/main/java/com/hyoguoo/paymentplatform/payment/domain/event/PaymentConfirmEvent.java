package com.hyoguoo.paymentplatform.payment.domain.event;

import lombok.Value;

@Value
public class PaymentConfirmEvent {

    String orderId;

    public static PaymentConfirmEvent of(String orderId) {
        return new PaymentConfirmEvent(orderId);
    }
}
