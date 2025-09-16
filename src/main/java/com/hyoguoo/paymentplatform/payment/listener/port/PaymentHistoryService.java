package com.hyoguoo.paymentplatform.payment.listener.port;

import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;

public interface PaymentHistoryService {

    void recordPaymentHistory(PaymentHistoryEvent event);
}
