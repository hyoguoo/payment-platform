package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
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
    private final LocalDateTime executedAt;
    private final LocalDateTime approvedAt;
    private final Integer retryCount;
    private final String statusReason;
    private final List<PaymentOrderResult> paymentOrderList;
    private final LocalDateTime createdAt;
    private final Boolean isPaymentDone;
    private final Long totalAmount;

    public static PaymentEventResult from(PaymentEvent paymentEvent) {
        List<PaymentOrderResult> orderResponses = paymentEvent.getPaymentOrderList() != null
                ? paymentEvent.getPaymentOrderList().stream()
                .map(PaymentOrderResult::from)
                .toList()
                : List.of();

        Long totalAmount = orderResponses.stream()
                .mapToLong(order -> order.getAmount() != null ? order.getAmount() : 0L)
                .sum();

        return PaymentEventResult.builder()
                .id(paymentEvent.getId())
                .buyerId(paymentEvent.getBuyerId())
                .sellerId(paymentEvent.getSellerId())
                .orderName(paymentEvent.getOrderName())
                .orderId(paymentEvent.getOrderId())
                .paymentKey(paymentEvent.getPaymentKey())
                .status(paymentEvent.getStatus())
                .executedAt(paymentEvent.getExecutedAt())
                .approvedAt(paymentEvent.getApprovedAt())
                .retryCount(paymentEvent.getRetryCount())
                .statusReason(paymentEvent.getStatusReason())
                .paymentOrderList(orderResponses)
                .createdAt(paymentEvent.getCreatedAt())
                .isPaymentDone(paymentEvent.getStatus() == PaymentEventStatus.DONE)
                .totalAmount(totalAmount)
                .build();
    }
}
