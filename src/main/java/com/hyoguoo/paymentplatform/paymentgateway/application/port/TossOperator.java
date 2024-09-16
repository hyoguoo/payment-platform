package com.hyoguoo.paymentplatform.paymentgateway.application.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;

public interface TossOperator {

    TossPaymentDetails findPaymentInfoByOrderId(String orderId);

    TossPaymentDetails confirmPayment(TossConfirmRequest tossConfirmRequest, String idempotencyKey);

    TossPaymentDetails cancelPayment(
            TossCancelRequest tossCancelRequest,
            String idempotencyKey
    );
}
