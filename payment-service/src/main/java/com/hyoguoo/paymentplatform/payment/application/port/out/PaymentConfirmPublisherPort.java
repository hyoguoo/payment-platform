package com.hyoguoo.paymentplatform.payment.application.port.out;

import java.math.BigDecimal;

public interface PaymentConfirmPublisherPort {

    void publish(String orderId, Long userId, BigDecimal amount, String paymentKey);
}
