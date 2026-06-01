package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PaymentEventResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;
import java.time.Instant;
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
    private final PaymentGatewayType gatewayType;
    private final String statusReason;
    private final Long buyerId;
    private final Long sellerId;
    private final Integer retryCount;
    // TODO T3: 시각 필드 → Instant (D1/D3). PaymentEventResult 전환에 따라 동반 전환.
    private final Instant createdAt;
    private final Instant executedAt;
    private final Instant approvedAt;

    public static PaymentEventResponse from(PaymentEventResult result) {
        return PaymentEventResponse.builder()
                .id(result.getId())
                .orderId(result.getOrderId())
                .orderName(result.getOrderName())
                .paymentKey(result.getPaymentKey())
                .totalAmount(result.getTotalAmount())
                .status(result.getStatus())
                .gatewayType(result.getGatewayType())
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
