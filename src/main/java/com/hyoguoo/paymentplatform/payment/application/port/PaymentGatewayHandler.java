package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import java.util.Optional;

public interface PaymentGatewayHandler {

    TossPaymentInfo getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo);

    TossPaymentInfo cancelPayment(TossCancelInfo tossCancelRequest);
}
