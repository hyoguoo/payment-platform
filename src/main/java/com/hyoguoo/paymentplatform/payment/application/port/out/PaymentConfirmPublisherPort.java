package com.hyoguoo.paymentplatform.payment.application.port.out;

public interface PaymentConfirmPublisherPort {

    void publish(String orderId);
}
