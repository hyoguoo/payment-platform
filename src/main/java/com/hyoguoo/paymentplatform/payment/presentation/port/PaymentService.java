package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.payment.application.dto.response.TossPaymentDetails;
import java.util.Optional;

public interface PaymentService {

    TossPaymentDetails getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentDetails> findPaymentInfoByOrderId(String orderId);

    TossPaymentDetails confirmPayment(TossConfirmRequest tossConfirmRequest, String idempotencyKey);

    TossPaymentDetails cancelPayment(
            TossCancelRequest tossCancelRequest,
            String idempotencyKey
    );
}
