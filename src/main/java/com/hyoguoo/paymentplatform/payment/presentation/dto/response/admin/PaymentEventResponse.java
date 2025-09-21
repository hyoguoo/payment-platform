package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentEventResponse {

    private final Long id;
    private final String orderId;
    private final String orderName;
    private final String paymentKey;
    private final BigDecimal totalAmount;
    private final PaymentEventStatus status;
    private final String statusReason;
    private final Long buyerId;
    private final Long sellerId;
    private final Integer retryCount;
    private final LocalDateTime createdAt;
    private final LocalDateTime executedAt;
    private final LocalDateTime approvedAt;

    public static PaymentEventResponse from(PaymentEventResult result) {
        return PaymentEventResponse.builder()
                .id(result.getId())
                .orderId(result.getOrderId())
                .orderName(result.getOrderName())
                .paymentKey(result.getPaymentKey())
                .totalAmount(result.getTotalAmount())
                .status(result.getStatus())
                .statusReason(result.getStatusReason())
                .buyerId(result.getBuyerId())
                .sellerId(result.getSellerId())
                .retryCount(result.getRetryCount())
                .createdAt(result.getCreatedAt())
                .executedAt(result.getExecutedAt())
                .approvedAt(result.getApprovedAt())
                .build();
    }
}
