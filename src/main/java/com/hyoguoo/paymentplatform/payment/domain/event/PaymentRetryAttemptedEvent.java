package com.hyoguoo.paymentplatform.payment.domain.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentRetryAttemptedEvent extends PaymentHistoryEvent {

    private final Integer retryCount;

    public PaymentRetryAttemptedEvent(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            Integer retryCount,
            LocalDateTime occurredAt
    ) {
        super(paymentEventId, orderId, previousStatus, currentStatus, reason, occurredAt);
        this.retryCount = retryCount;
    }

    public static PaymentRetryAttemptedEvent of(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            Integer retryCount,
            LocalDateTime occurredAt
    ) {
        return new PaymentRetryAttemptedEvent(
                paymentEventId,
                orderId,
                previousStatus,
                currentStatus,
                reason,
                retryCount,
                occurredAt
        );
    }

    @Override
    public PaymentHistoryEventType getEventType() {
        return PaymentHistoryEventType.RETRY_ATTEMPT;
    }
}
