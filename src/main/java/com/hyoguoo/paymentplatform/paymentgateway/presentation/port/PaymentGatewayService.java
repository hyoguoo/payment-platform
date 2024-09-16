package com.hyoguoo.paymentplatform.paymentgateway.presentation.port;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import java.util.Optional;

public interface PaymentGatewayService {

    TossPaymentInfo getPaymentResultByOrderId(String orderId);

    Optional<TossPaymentInfo> findPaymentResultByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmCommand tossConfirmCommand, String idempotencyKey);

    TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    );
}
