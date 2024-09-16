package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossDetailsClientResponse;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.port.PaymentGatewayService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentGatewayInternalReceiver {

    private final PaymentGatewayService paymentGatewayService;

    public TossDetailsClientResponse getPaymentInfoByOrderId(String orderId) {
        TossPaymentDetails paymentInfoByOrderId = paymentGatewayService.getPaymentInfoByOrderId(orderId);
        return PaymentGatewayPresentationMapper.toTossDetailsClientResponse(paymentInfoByOrderId);
    }

    public Optional<TossDetailsClientResponse> findPaymentInfoByOrderId(String orderId) {
        Optional<TossPaymentDetails> paymentInfoByOrderId = paymentGatewayService.findPaymentInfoByOrderId(
                orderId
        );
        return paymentInfoByOrderId.map(PaymentGatewayPresentationMapper::toTossDetailsClientResponse);
    }

    public TossDetailsClientResponse confirmPayment(
            TossConfirmClientRequest tossConfirmClientRequest
    ) {
        TossPaymentDetails tossPaymentDetails = paymentGatewayService.confirmPayment(
                PaymentGatewayPresentationMapper.toTossConfirmRequest(tossConfirmClientRequest),
                tossConfirmClientRequest.getIdempotencyKey()
        );
        return PaymentGatewayPresentationMapper.toTossDetailsClientResponse(tossPaymentDetails);
    }

    public TossDetailsClientResponse cancelPayment(
            TossCancelClientRequest tossCancelClientRequest
    ) {
        TossPaymentDetails tossPaymentDetails = paymentGatewayService.cancelPayment(
                PaymentGatewayPresentationMapper.toTossCancelRequest(tossCancelClientRequest),
                tossCancelClientRequest.getIdempotencyKey()
        );
        return PaymentGatewayPresentationMapper.toTossDetailsClientResponse(tossPaymentDetails);
    }
}
