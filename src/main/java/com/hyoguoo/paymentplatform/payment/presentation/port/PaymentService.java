package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;

public interface PaymentService {

    CheckoutResult checkout(CheckoutCommand request);
}
