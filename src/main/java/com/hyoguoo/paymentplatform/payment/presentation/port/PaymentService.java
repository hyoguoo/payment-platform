package com.hyoguoo.paymentplatform.payment.presentation.port;

import java.util.Optional;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;

public interface PaymentService {

    TossPaymentResponse getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentResponse> findPaymentInfoByOrderId(String orderId);

    TossPaymentResponse confirmPayment(
            TossConfirmRequest tossConfirmRequest,
            String idempotencyKey
    );

    TossPaymentResponse cancelPayment(
            String paymentKey,
            String idempotencyKey,
            TossCancelRequest tossCancelRequest
    );
}
