package com.hyoguoo.paymentplatform.payment.domain.factory;

import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentCreatedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentRetryAttemptedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentStatusChangedEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentHistoryException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import org.springframework.stereotype.Component;

@Component
public class PaymentHistoryFactory {

    public PaymentHistory createFromEvent(PaymentHistoryEvent event) {
        return switch (event) {
            case PaymentCreatedEvent created -> PaymentHistory.createPaymentCreated(
                    created.getPaymentEventId(),
                    created.getOrderId(),
                    created.getCurrentStatus(),
                    created.getReason(),
                    created.getOccurredAt()
            );

            case PaymentStatusChangedEvent statusChanged -> PaymentHistory.createStatusChange(
                    statusChanged.getPaymentEventId(),
                    statusChanged.getOrderId(),
                    statusChanged.getPreviousStatus(),
                    statusChanged.getCurrentStatus(),
                    statusChanged.getReason(),
                    statusChanged.getOccurredAt()
            );

            case PaymentRetryAttemptedEvent retryAttempt -> PaymentHistory.createRetryAttempt(
                    retryAttempt.getPaymentEventId(),
                    retryAttempt.getOrderId(),
                    retryAttempt.getPreviousStatus(),
                    retryAttempt.getCurrentStatus(),
                    retryAttempt.getReason(),
                    retryAttempt.getRetryCount(),
                    retryAttempt.getOccurredAt()
            );

            default -> throw PaymentHistoryException.of(PaymentErrorCode.UNSUPPORTED_EVENT_TYPE);
        };
    }
}
