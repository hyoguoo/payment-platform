package com.hyoguoo.paymentplatform.order.presentation;

import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderCancelRequest;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderConfirmRequest;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderCreateRequest;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderPresentationMapper {

    public static OrderCreateInfo toOrderCreateInfo(OrderCreateRequest request, Long userId) {
        return OrderCreateInfo.builder()
                .userId(userId)
                .amount(request.getAmount())
                .orderProduct(request.getOrderProduct())
                .build();
    }

    public static OrderConfirmInfo toOrderConfirmInfo(OrderConfirmRequest request, Long userId) {
        return OrderConfirmInfo.builder()
                .userId(userId)
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .paymentKey(request.getPaymentKey())
                .build();
    }


    public static OrderCancelInfo toOrderCancelInfo(OrderCancelRequest request) {
        return OrderCancelInfo.builder()
                .orderId(request.getOrderId())
                .cancelReason(request.getCancelReason())
                .build();
    }
}
