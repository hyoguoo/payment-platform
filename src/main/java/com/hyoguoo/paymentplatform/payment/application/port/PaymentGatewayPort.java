package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;

public interface PaymentGatewayPort {

    TossPaymentInfo getPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmGatewayCommand tossConfirmGatewayCommand);

    TossPaymentInfo cancelPayment(TossCancelGatewayCommand tossCancelGatewayCommand);
}
