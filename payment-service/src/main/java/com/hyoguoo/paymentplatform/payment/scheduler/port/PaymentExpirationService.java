package com.hyoguoo.paymentplatform.payment.scheduler.port;

public interface PaymentExpirationService {

    void expireOldReadyPayments();
}
