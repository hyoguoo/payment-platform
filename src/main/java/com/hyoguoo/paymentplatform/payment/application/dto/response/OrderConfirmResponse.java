package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class OrderConfirmResponse {

    private final String orderId;
    private final BigDecimal amount;

    public OrderConfirmResponse(PaymentOrder paymentOrder) {
        this.orderId = paymentOrder.getOrderId();
        this.amount = paymentOrder.getTotalAmount();
    }
}
