package com.hyoguoo.paymentplatform.payment.application.port.in;

public interface PaymentExpirationService {

    void expireOldReadyPayments();
}
