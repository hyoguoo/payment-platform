package com.hyoguoo.paymentplatform.paymentgateway.presentation;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.response.TossPaymentResult;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.port.PaymentGatewayService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentGatewayInternalReceiver {

    private final PaymentGatewayService paymentGatewayService;

    public TossPaymentResponse getPaymentInfoByOrderId(String orderId) {
        TossPaymentResult paymentInfoByOrderId = paymentGatewayService.getPaymentResultByOrderId(orderId);
        return PaymentGatewayPresentationMapper.toTossDetailsResponse(paymentInfoByOrderId);
    }

    public Optional<TossPaymentResponse> findPaymentInfoByOrderId(String orderId) {
        Optional<TossPaymentResult> paymentInfoByOrderId = paymentGatewayService.findPaymentResultByOrderId(
                orderId
        );
        return paymentInfoByOrderId.map(PaymentGatewayPresentationMapper::toTossDetailsResponse);
    }

    public TossPaymentResponse confirmPayment(
            TossConfirmRequest tossConfirmRequest
    ) {
        TossPaymentResult tossPaymentResult = paymentGatewayService.confirmPayment(
                PaymentGatewayPresentationMapper.toTossConfirmCommand(tossConfirmRequest),
                tossConfirmRequest.getIdempotencyKey()
        );
        return PaymentGatewayPresentationMapper.toTossDetailsResponse(tossPaymentResult);
    }

    public TossPaymentResponse cancelPayment(
            TossCancelRequest tossCancelRequest
    ) {
        TossPaymentResult tossPaymentResult = paymentGatewayService.cancelPayment(
                PaymentGatewayPresentationMapper.toTossCancelCommand(tossCancelRequest),
                tossCancelRequest.getIdempotencyKey()
        );
        return PaymentGatewayPresentationMapper.toTossDetailsResponse(tossPaymentResult);
    }
}
