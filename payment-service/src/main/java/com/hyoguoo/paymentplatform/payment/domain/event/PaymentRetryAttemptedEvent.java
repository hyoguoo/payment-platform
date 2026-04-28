package com.hyoguoo.paymentplatform.payment.domain.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentRetryAttemptedEvent extends PaymentHistoryEvent {

    public PaymentRetryAttemptedEvent(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            LocalDateTime occurredAt
    ) {
        super(paymentEventId, orderId, previousStatus, currentStatus, reason, occurredAt);
    }

    public static PaymentRetryAttemptedEvent of(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            LocalDateTime occurredAt
    ) {
        return new PaymentRetryAttemptedEvent(
                paymentEventId,
                orderId,
                previousStatus,
                currentStatus,
                reason,
                occurredAt
        );
    }

    @Override
    public PaymentHistoryEventType getEventType() {
        return PaymentHistoryEventType.RETRY_ATTEMPT;
    }
}
