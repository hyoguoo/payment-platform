package com.hyoguoo.paymentplatform.paymentgateway.application.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;

public interface TossOperator {

    TossPaymentInfo findPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmCommand tossConfirmCommand, String idempotencyKey)
            throws PaymentGatewayApiException;

    TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    );
}
