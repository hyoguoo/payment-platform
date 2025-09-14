package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;

public interface PaymentHistoryRepository {

    PaymentHistory save(PaymentHistory paymentHistory);
}
