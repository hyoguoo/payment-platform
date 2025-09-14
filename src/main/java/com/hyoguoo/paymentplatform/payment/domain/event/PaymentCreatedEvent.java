package com.hyoguoo.paymentplatform.payment.domain.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentCreatedEvent extends PaymentHistoryEvent {
    
    public PaymentCreatedEvent(
            Long paymentEventId,
            String orderId,
            PaymentEventStatus initialStatus,
            String reason,
            LocalDateTime occurredAt
    ) {
        super(paymentEventId, orderId, null, initialStatus, reason, occurredAt);
    }
    
    @Override
    public PaymentHistoryEventType getEventType() {
        return PaymentHistoryEventType.PAYMENT_CREATED;
    }
}
