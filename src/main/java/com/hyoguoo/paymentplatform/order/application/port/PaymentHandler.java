package com.hyoguoo.paymentplatform.order.application.port;

import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import java.util.Optional;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;

public interface PaymentHandler {

    TossPaymentInfo getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmRequest tossConfirmRequest, String idempotencyKey);

    TossPaymentInfo cancelPayment(String paymentKey, String idempotencyKey, TossCancelRequest tossCancelRequest);
}
