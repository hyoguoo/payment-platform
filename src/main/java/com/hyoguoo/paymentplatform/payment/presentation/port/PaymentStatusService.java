package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;

public interface PaymentStatusService {

    PaymentStatusResult getPaymentStatus(String orderId);
}
