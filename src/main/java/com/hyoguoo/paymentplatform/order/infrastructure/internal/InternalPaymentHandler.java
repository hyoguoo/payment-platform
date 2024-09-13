package com.hyoguoo.paymentplatform.order.infrastructure.internal;

import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.port.PaymentHandler;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.infrastructure.OrderInfrastructureMapper;
import com.hyoguoo.paymentplatform.payment.presentation.PaymentInternalReceiver;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.TossDetailsClientResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentHandler implements PaymentHandler {

    private final PaymentInternalReceiver paymentInternalReceiver;

    @Override
    public TossPaymentInfo getPaymentInfoByOrderId(String orderId) {
        return OrderInfrastructureMapper.toTossPaymentInfo(
                paymentInternalReceiver.getPaymentInfoByOrderId(orderId)
        );
    }

    @Override
    public Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId) {
        return paymentInternalReceiver
                .findPaymentInfoByOrderId(orderId)
                .map(OrderInfrastructureMapper::toTossPaymentInfo);
    }

    @Override
    public TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo) {
        TossDetailsClientResponse tossPaymentDetails = paymentInternalReceiver.confirmPayment(
                OrderInfrastructureMapper.toTossConfirmRequest(tossConfirmInfo)
        );

        return OrderInfrastructureMapper.toTossPaymentInfo(tossPaymentDetails);
    }

    @Override
    public TossPaymentInfo cancelPayment(TossCancelInfo tossCancelInfo) {
        return OrderInfrastructureMapper.toTossPaymentInfo(
                paymentInternalReceiver.cancelPayment(OrderInfrastructureMapper.toTossCancelRequest(tossCancelInfo))
        );
    }
}
