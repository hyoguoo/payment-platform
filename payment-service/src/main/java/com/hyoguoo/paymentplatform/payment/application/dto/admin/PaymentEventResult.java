package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentEventResult {

    private final Long id;
    private final Long buyerId;
    private final Long sellerId;
    private final String orderName;
    private final String orderId;
    private final String paymentKey;
    private final PaymentEventStatus status;
    private final PaymentGatewayType gatewayType;
    // TODO T3: 시각 필드 → Instant (D1/D3). PaymentEvent.executedAt/approvedAt 전환에 따라 동반 전환.
    private final Instant executedAt;
    private final Instant approvedAt;
    private final Integer retryCount;
    private final String statusReason;
    private final List<PaymentOrderResult> paymentOrderList;
    private final Instant createdAt;
    private final Boolean isPaymentDone;
    private final BigDecimal totalAmount;

    public static PaymentEventResult from(PaymentEvent paymentEvent) {
        List<PaymentOrderResult> orderResponses = paymentEvent.getPaymentOrderList() != null
                ? paymentEvent.getPaymentOrderList().stream()
                .map(PaymentOrderResult::from)
                .toList()
                : List.of();

        return PaymentEventResult.builder()
                .id(paymentEvent.getId())
                .buyerId(paymentEvent.getBuyerId())
                .sellerId(paymentEvent.getSellerId())
                .orderName(paymentEvent.getOrderName())
                .orderId(paymentEvent.getOrderId())
                .paymentKey(paymentEvent.getPaymentKey())
                .status(paymentEvent.getStatus())
                .gatewayType(paymentEvent.getGatewayType())
                .executedAt(paymentEvent.getExecutedAt())
                .approvedAt(paymentEvent.getApprovedAt())
                .retryCount(paymentEvent.getRetryCount())
                .statusReason(paymentEvent.getStatusReason())
                .paymentOrderList(orderResponses)
                .createdAt(paymentEvent.getCreatedAt())
                .isPaymentDone(paymentEvent.getStatus() == PaymentEventStatus.DONE)
                .totalAmount(paymentEvent.getTotalAmount())
                .build();
    }
}
