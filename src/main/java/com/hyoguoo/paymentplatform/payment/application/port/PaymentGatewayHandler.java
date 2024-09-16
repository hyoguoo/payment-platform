package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import java.util.Optional;

public interface PaymentGatewayHandler {

    TossPaymentInfo getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmGatewayCommand tossConfirmGatewayCommand);

    TossPaymentInfo cancelPayment(TossCancelGatewayCommand tossCancelGatewayCommand);
}