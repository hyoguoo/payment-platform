package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import lombok.Getter;

@Getter
public class OrderCreateResponse {

    private final String orderId;

    public OrderCreateResponse(PaymentOrder paymentOrder) {
        this.orderId = paymentOrder.getOrderId();
    }
}
