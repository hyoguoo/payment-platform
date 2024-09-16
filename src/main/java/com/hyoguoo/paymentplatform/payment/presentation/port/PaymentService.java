package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderFindDetailResponse;
import com.hyoguoo.paymentplatform.payment.application.dto.response.OrderListResponse;

public interface PaymentService {

    OrderListResponse findOrderList(int page, int size);

    OrderCancelResponse cancelOrder(OrderCancelInfo orderCancelInfo);

    OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id);

    OrderCreateResponse createOrder(OrderCreateInfo orderCreateInfo);

    OrderConfirmResponse confirmOrder(OrderConfirmInfo orderConfirmInfo);
}
