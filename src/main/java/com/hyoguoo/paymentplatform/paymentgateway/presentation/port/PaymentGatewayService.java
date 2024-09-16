package com.hyoguoo.paymentplatform.paymentgateway.presentation.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentResult;
import java.util.Optional;

public interface PaymentGatewayService {

    TossPaymentResult getPaymentResultByOrderId(String orderId);

    Optional<TossPaymentResult> findPaymentResultByOrderId(String orderId);

    TossPaymentResult confirmPayment(TossConfirmCommand tossConfirmCommand, String idempotencyKey);

    TossPaymentResult cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    );
}
