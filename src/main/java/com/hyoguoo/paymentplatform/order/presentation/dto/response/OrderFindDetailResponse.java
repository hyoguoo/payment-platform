package com.hyoguoo.paymentplatform.order.presentation.dto.response;

import com.hyoguoo.paymentplatform.order.domain.OrderInfo;
import com.hyoguoo.paymentplatform.order.domain.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class OrderFindDetailResponse {

    private final Long id;
    private final String orderId;
    private final BigDecimal amount;
    private final OrderStatus orderStatus;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private String paymentKey;

    public OrderFindDetailResponse(OrderInfo orderInfo) {
        this.id = orderInfo.getId();
        this.orderId = orderInfo.getOrderId();
        this.amount = orderInfo.getTotalAmount();
        this.paymentKey = orderInfo.getPaymentKey();
        this.requestedAt = orderInfo.getRequestedAt();
        this.approvedAt = orderInfo.getApprovedAt();
        this.orderStatus = orderInfo.getOrderStatus();
    }
}
