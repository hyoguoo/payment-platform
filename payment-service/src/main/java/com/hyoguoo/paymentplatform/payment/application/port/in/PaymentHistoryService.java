package com.hyoguoo.paymentplatform.payment.application.port.in;

import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;

public interface PaymentHistoryService {

    void recordPaymentHistory(PaymentHistoryEvent event);
}
