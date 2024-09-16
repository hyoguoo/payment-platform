package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class OrderFindResponse {

    private final Long id;
    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
    private final LocalDateTime requestedAt;
    private final LocalDateTime approvedAt;
    private final PaymentOrderStatus paymentOrderStatus;

    public OrderFindResponse(PaymentOrder paymentOrder) {
        this.id = paymentOrder.getId();
        this.orderId = paymentOrder.getOrderId();
        this.amount = paymentOrder.getTotalAmount();
        this.paymentKey = paymentOrder.getPaymentKey();
        this.requestedAt = paymentOrder.getRequestedAt();
        this.approvedAt = paymentOrder.getApprovedAt();
        this.paymentOrderStatus = paymentOrder.getPaymentOrderStatus();
    }
}
