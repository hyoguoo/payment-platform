package com.hyoguoo.paymentplatform.payment.domain.event;

import java.math.BigDecimal;
import lombok.ToString;
import lombok.Value;

@Value
public class PaymentConfirmEvent {

    String orderId;
    Long userId;
    BigDecimal amount;
    @ToString.Exclude
    String paymentKey;

    public static PaymentConfirmEvent of(String orderId, Long userId, BigDecimal amount, String paymentKey) {
        return new PaymentConfirmEvent(orderId, userId, amount, paymentKey);
    }
}
