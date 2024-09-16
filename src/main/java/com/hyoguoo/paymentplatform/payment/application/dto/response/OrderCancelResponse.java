package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import lombok.Getter;

@Getter
public class OrderCancelResponse {

    private final Long id;

    public OrderCancelResponse(PaymentOrder paymentOrder) {
        this.id = paymentOrder.getId();
    }
}
