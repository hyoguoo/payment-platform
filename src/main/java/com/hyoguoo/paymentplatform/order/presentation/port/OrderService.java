package com.hyoguoo.paymentplatform.order.presentation.port;

import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCancelResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderFindDetailResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderListResponse;

public interface OrderService {

    OrderListResponse findOrderList(int page, int size);

    OrderCancelResponse cancelOrder(OrderCancelInfo orderCancelInfo);

    OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id);

    OrderCreateResponse createOrder(OrderCreateInfo orderCreateInfo);

    OrderConfirmResponse confirmOrder(OrderConfirmInfo orderConfirmInfo);
}
