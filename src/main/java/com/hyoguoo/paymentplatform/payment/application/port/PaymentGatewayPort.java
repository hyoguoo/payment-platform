package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;

public interface PaymentGatewayPort {

    PaymentConfirmResult confirm(PaymentConfirmRequest request);

    PaymentCancelResult cancel(PaymentCancelRequest request);
}
