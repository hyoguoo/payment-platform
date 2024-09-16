package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;

public interface PaymentFindService {

    PaymentEvent getPaymentEventByOrderId(String orderId);
}
