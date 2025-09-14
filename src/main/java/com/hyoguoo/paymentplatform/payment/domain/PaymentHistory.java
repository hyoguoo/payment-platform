package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
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
    private Integer retryCount;
    private LocalDateTime changeStatusAt;

    public static PaymentHistory createPaymentCreated(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus initialStatus,
            String reason,
            LocalDateTime changeStatusAt
    ) {
        return PaymentHistory.builder()
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .previousStatus(null)
                .currentStatus(initialStatus)
                .reason(reason)
                .retryCount(null)
                .changeStatusAt(changeStatusAt)
                .build();
    }

    public static PaymentHistory createStatusChange(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            LocalDateTime changeStatusAt
    ) {
        return PaymentHistory.builder()
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .previousStatus(previousStatus)
                .currentStatus(currentStatus)
                .reason(reason)
                .retryCount(null)
                .changeStatusAt(changeStatusAt)
                .build();
    }

    public static PaymentHistory createRetryAttempt(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            Integer retryCount,
            LocalDateTime changeStatusAt
    ) {
        return PaymentHistory.builder()
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .previousStatus(previousStatus)
                .currentStatus(currentStatus)
                .reason(reason)
                .retryCount(retryCount)
                .changeStatusAt(changeStatusAt)
                .build();
    }
}
