package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;

public interface PaymentFindService {

    PaymentEvent getPaymentEventByOrderId(String orderId);

    List<PaymentOrder> getPaymentOrderListByPaymentEventId(Long paymentEventId);
}
