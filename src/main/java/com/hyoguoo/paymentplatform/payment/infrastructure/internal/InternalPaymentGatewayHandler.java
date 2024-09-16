package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentOrderInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossDetailsClientResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentGatewayHandler implements PaymentGatewayHandler {

    private final PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        return PaymentOrderInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.getPaymentInfoByOrderId(orderId)
        );
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId) {
        return paymentGatewayInternalReceiver
                .findPaymentInfoByOrderId(orderId)
                .map(PaymentOrderInfrastructureMapper::toTossPaymentInfo);
    }

    @Override
    public TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo) {
        TossDetailsClientResponse tossPaymentDetails = paymentGatewayInternalReceiver.confirmPayment(
                PaymentOrderInfrastructureMapper.toTossConfirmRequest(tossConfirmInfo)
        );

        return PaymentOrderInfrastructureMapper.toTossPaymentInfo(tossPaymentDetails);
    }

    @Override
    public TossPaymentInfo cancelPayment(TossCancelInfo tossCancelInfo) {
        return PaymentOrderInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.cancelPayment(PaymentOrderInfrastructureMapper.toTossCancelRequest(tossCancelInfo))
        );
    }
}
