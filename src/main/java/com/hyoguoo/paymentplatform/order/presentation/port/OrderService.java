package com.hyoguoo.paymentplatform.order.presentation.port;

import study.paymentintegrationserver.dto.order.OrderCancelRequest;
import study.paymentintegrationserver.dto.order.OrderCancelResponse;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.dto.order.OrderFindDetailResponse;
import study.paymentintegrationserver.dto.order.OrderListResponse;

public interface OrderService {

    OrderListResponse findOrderList(int page, int size);

    OrderCancelResponse cancelOrder(OrderCancelRequest orderCancelRequest);

    OrderFindDetailResponse getOrderDetailsByIdAndUpdatePaymentInfo(Long id);

    OrderCreateResponse createOrder(OrderCreateRequest orderCreateRequest);

    OrderConfirmResponse confirmOrder(OrderConfirmRequest orderConfirmRequest);
}
