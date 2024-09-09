package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.TossCancelClientRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.TossConfirmClientRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.TossDetailsClientResponse;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentInternalReceiver {

    private final PaymentService paymentService;

    public TossDetailsClientResponse getPaymentInfoByOrderId(String orderId) {
        TossPaymentDetails paymentInfoByOrderId = paymentService.getPaymentInfoByOrderId(orderId);
        return PaymentPresentationMapper.toTossDetailsClientResponse(paymentInfoByOrderId);
    }

    public Optional<TossDetailsClientResponse> findPaymentInfoByOrderId(String orderId) {
        Optional<TossPaymentDetails> paymentInfoByOrderId = paymentService.findPaymentInfoByOrderId(
                orderId
        );
        return paymentInfoByOrderId.map(PaymentPresentationMapper::toTossDetailsClientResponse);
    }

    public TossDetailsClientResponse confirmPayment(
            TossConfirmClientRequest tossConfirmClientRequest
    ) {
        TossPaymentDetails tossPaymentDetails = paymentService.confirmPayment(
                PaymentPresentationMapper.toTossConfirmRequest(tossConfirmClientRequest),
                tossConfirmClientRequest.getIdempotencyKey()
        );
        return PaymentPresentationMapper.toTossDetailsClientResponse(tossPaymentDetails);
    }

    public TossDetailsClientResponse cancelPayment(
            TossCancelClientRequest tossCancelClientRequest
    ) {
        TossPaymentDetails tossPaymentDetails = paymentService.cancelPayment(
                PaymentPresentationMapper.toTossCancelRequest(tossCancelClientRequest),
                tossCancelClientRequest.getIdempotencyKey()
        );
        return PaymentPresentationMapper.toTossDetailsClientResponse(tossPaymentDetails);
    }
}
