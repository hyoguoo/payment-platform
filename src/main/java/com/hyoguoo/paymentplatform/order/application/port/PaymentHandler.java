package com.hyoguoo.paymentplatform.order.application.port;

import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import java.util.Optional;

public interface PaymentHandler {

    TossPaymentInfo getPaymentInfoByOrderId(String orderId);

    Optional<TossPaymentInfo> findPaymentInfoByOrderId(String orderId);

    TossPaymentInfo confirmPayment(TossConfirmInfo tossConfirmInfo);

    TossPaymentInfo cancelPayment(TossCancelInfo tossCancelRequest);
}
