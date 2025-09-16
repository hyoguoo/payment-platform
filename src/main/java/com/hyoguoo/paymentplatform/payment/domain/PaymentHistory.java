package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentHistory {

    private Long id;
    private Long paymentEventId;
    private String orderId;
    private PaymentEventStatus previousStatus;
    private PaymentEventStatus currentStatus;
    private String reason;
    private LocalDateTime changeStatusAt;

    public static PaymentHistory from(PaymentHistoryEvent event) {
        return PaymentHistory.builder()
                .paymentEventId(event.getPaymentEventId())
                .orderId(event.getOrderId())
                .previousStatus(event.getPreviousStatus())
                .currentStatus(event.getCurrentStatus())
                .reason(event.getReason())
                .changeStatusAt(event.getOccurredAt())
                .build();
    }
}
