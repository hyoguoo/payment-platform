package com.hyoguoo.paymentplatform.order.infrastucture.internal;

import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.application.port.PaymentHandler;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import study.paymentintegrationserver.dto.toss.TossCancelRequest;
import study.paymentintegrationserver.dto.toss.TossConfirmRequest;
import study.paymentintegrationserver.dto.toss.TossPaymentResponse;

@Component
@RequiredArgsConstructor
public class InternalPaymentHandler implements PaymentHandler {

    private final PaymentService paymentService;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        TossPaymentResponse paymentInfoByOrderId = paymentService.getPaymentInfoByOrderId(orderId);

        return TossPaymentInfo.of(paymentInfoByOrderId);
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId) {
        Optional<TossPaymentResponse> paymentInfoByOrderId = paymentService.findPaymentInfoByOrderId(orderId);

        return paymentInfoByOrderId.map(TossPaymentInfo::of);
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmRequest tossConfirmRequest,
            String idempotencyKey
    ) {
        TossPaymentResponse paymentResponse = paymentService.confirmPayment(tossConfirmRequest, idempotencyKey);

        return TossPaymentInfo.of(paymentResponse);
    }

    @Override
    public TossPaymentInfo cancelPayment(
            String paymentKey,
            String idempotencyKey,
            TossCancelRequest tossCancelRequest
    ) {
        TossPaymentResponse paymentResponse = paymentService.cancelPayment(paymentKey, idempotencyKey, tossCancelRequest);

        return TossPaymentInfo.of(paymentResponse);
    }
}
