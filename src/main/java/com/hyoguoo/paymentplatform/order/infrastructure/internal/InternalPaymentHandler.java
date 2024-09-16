package com.hyoguoo.paymentplatform.order.infrastructure.internal;

import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.port.PaymentHandler;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.infrastructure.OrderInfrastructureMapper;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossDetailsClientResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentHandler implements PaymentHandler {

    private final PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        return OrderInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.getPaymentInfoByOrderId(orderId)
        );
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId) {
        return paymentGatewayInternalReceiver
                .findPaymentInfoByOrderId(orderId)
                .map(OrderInfrastructureMapper::toTossPaymentInfo);
    }

    @Override
    public TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo) {
        TossDetailsClientResponse tossPaymentDetails = paymentGatewayInternalReceiver.confirmPayment(
                OrderInfrastructureMapper.toTossConfirmRequest(tossConfirmInfo)
        );

        return OrderInfrastructureMapper.toTossPaymentInfo(tossPaymentDetails);
    }

    @Override
    public TossPaymentInfo cancelPayment(TossCancelInfo tossCancelInfo) {
        return OrderInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.cancelPayment(OrderInfrastructureMapper.toTossCancelRequest(tossCancelInfo))
        );
    }
}
