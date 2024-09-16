package com.hyoguoo.paymentplatform.paymentgateway.presentation.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;
import java.util.Optional;

public interface PaymentGatewayService {

    TossPaymentDetails getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentDetails> findPaymentInfoByOrderId(String orderId);

    TossPaymentDetails confirmPayment(TossConfirmRequest tossConfirmRequest, String idempotencyKey);

    TossPaymentDetails cancelPayment(
            TossCancelRequest tossCancelRequest,
            String idempotencyKey
    );
}
