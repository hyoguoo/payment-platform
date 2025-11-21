package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;

public interface PaymentGatewayStrategy {

    boolean supports(PaymentGatewayType type);

    PaymentConfirmResult confirm(PaymentConfirmRequest request);

    PaymentCancelResult cancel(PaymentCancelRequest request);

    PaymentStatusResult getStatus(String paymentKey);

    PaymentStatusResult getStatusByOrderId(String orderId);
}
