package com.hyoguoo.paymentplatform.order.application.dto.response;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import lombok.Getter;

@Getter
public class OrderCreateResponse {

    private final String orderId;

    public OrderCreateResponse(OrderInfo orderInfo) {
        this.orderId = orderInfo.getOrderId();
    }
}
