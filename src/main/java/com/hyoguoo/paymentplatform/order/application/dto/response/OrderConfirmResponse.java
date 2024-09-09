package com.hyoguoo.paymentplatform.order.application.dto.response;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class OrderConfirmResponse {

    private final String orderId;
    private final BigDecimal amount;

    public OrderConfirmResponse(OrderInfo orderInfo) {
        this.orderId = orderInfo.getOrderId();
        this.amount = orderInfo.getTotalAmount();
    }
}
