package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;

public interface PaymentHistoryRepository {

    PaymentHistory save(PaymentHistory paymentHistory);
}
