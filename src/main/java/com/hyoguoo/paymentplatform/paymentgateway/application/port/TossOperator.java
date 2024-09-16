package com.hyoguoo.paymentplatform.paymentgateway.application.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentResult;

public interface TossOperator {

    TossPaymentResult findPaymentInfoByOrderId(String orderId);

    TossPaymentResult confirmPayment(TossConfirmCommand tossConfirmCommand, String idempotencyKey);

    TossPaymentResult cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    );
}
