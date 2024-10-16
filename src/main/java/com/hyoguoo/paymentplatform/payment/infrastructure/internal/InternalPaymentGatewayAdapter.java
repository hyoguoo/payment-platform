package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentGatewayAdapter implements PaymentGatewayPort {

    private final PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.getPaymentInfoByOrderId(
                orderId
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }

    @Override
    public TossPaymentInfo confirmPayment(TossConfirmGatewayCommand tossConfirmGatewayCommand) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.confirmPayment(
                PaymentInfrastructureMapper.toTossConfirmRequest(tossConfirmGatewayCommand)
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }

    @Override
    public TossPaymentInfo cancelPayment(TossCancelGatewayCommand tossCancelGatewayCommand) {
        TossPaymentResponse tossPaymentResponse = paymentGatewayInternalReceiver.cancelPayment(
                PaymentInfrastructureMapper.toTossCancelRequest(tossCancelGatewayCommand)
        );

        return PaymentInfrastructureMapper.toTossPaymentInfo(tossPaymentResponse);
    }
}
