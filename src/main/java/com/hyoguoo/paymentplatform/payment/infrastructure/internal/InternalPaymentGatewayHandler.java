package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentGatewayHandler implements PaymentGatewayHandler {

    private final PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.getPaymentInfoByOrderId(
                orderId
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId) {
        Optional<TossPaymentResponse> tossPaymentResponse = paymentGatewayInternalReceiver
                .findPaymentInfoByOrderId(orderId);

        return tossPaymentResponse
                .map(PaymentInfrastructureMapper::toTossPaymentInfo);
    }

    @Override
    public TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.confirmPayment(
                PaymentInfrastructureMapper.toTossConfirmRequest(tossConfirmInfo)
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }

    @Override
    public TossPaymentInfo cancelPayment(TossCancelInfo tossCancelInfo) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.cancelPayment(
                PaymentInfrastructureMapper.toTossCancelRequest(tossCancelInfo)
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }
}
