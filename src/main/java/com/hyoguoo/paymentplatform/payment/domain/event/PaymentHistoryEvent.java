package com.hyoguoo.paymentplatform.payment.domain.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public abstract class PaymentHistoryEvent {
    
    private final Long paymentEventId;
    private final String orderId;
    private final PaymentEventStatus previousStatus;
    private final PaymentEventStatus currentStatus;
    private final String reason;
    private final LocalDateTime occurredAt;
    
    protected PaymentHistoryEvent(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus previousStatus,
            PaymentEventStatus currentStatus,
            String reason,
            LocalDateTime occurredAt
    ) {
        this.paymentEventId = paymentEventId;
        this.orderId = orderId;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
    
    public abstract PaymentHistoryEventType getEventType();
}
