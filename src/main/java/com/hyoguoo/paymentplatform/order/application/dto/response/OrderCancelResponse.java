package com.hyoguoo.paymentplatform.order.application.dto.response;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import lombok.Getter;

@Getter
public class OrderCancelResponse {

    private final Long id;

    public OrderCancelResponse(OrderInfo orderInfo) {
        this.id = orderInfo.getId();
    }
}
