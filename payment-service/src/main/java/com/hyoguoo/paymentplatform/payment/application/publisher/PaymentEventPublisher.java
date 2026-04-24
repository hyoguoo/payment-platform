package com.hyoguoo.paymentplatform.payment.application.publisher;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentCreatedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentRetryAttemptedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentStatusChangedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void publishPaymentCreated(PaymentEvent paymentEvent, String reason, LocalDateTime occurredAt) {
        applicationEventPublisher.publishEvent(
                PaymentCreatedEvent.of(
                        paymentEvent.getId(),
                        paymentEvent.getOrderId(),
                        paymentEvent.getStatus(),
                        reason,
                        occurredAt
                )
        );
        LogFmt.info(log, LogDomain.PAYMENT, EventType.DOMAIN_EVENT_CREATED_PUBLISHED,
                () -> "orderId=" + paymentEvent.getOrderId() + " status=" + paymentEvent.getStatus());
    }

    public void publishStatusChange(PaymentEvent paymentEvent,
            PaymentEventStatus previousStatus,
            String reason,
            LocalDateTime occurredAt) {
        applicationEventPublisher.publishEvent(
                PaymentStatusChangedEvent.of(
                        paymentEvent.getId(),
                        paymentEvent.getOrderId(),
                        previousStatus,
                        paymentEvent.getStatus(),
                        reason,
                        occurredAt
                )
        );
        LogFmt.info(log, LogDomain.PAYMENT, EventType.DOMAIN_EVENT_STATUS_CHANGE_PUBLISHED,
                () -> "orderId=" + paymentEvent.getOrderId()
                        + " " + previousStatus + "->" + paymentEvent.getStatus());
    }

    public void publishRetryAttempt(PaymentEvent paymentEvent,
            PaymentEventStatus previousStatus,
            String reason,
            LocalDateTime occurredAt) {
        applicationEventPublisher.publishEvent(
                PaymentRetryAttemptedEvent.of(
                        paymentEvent.getId(),
                        paymentEvent.getOrderId(),
                        previousStatus,
                        paymentEvent.getStatus(),
                        reason,
                        occurredAt
                )
        );
        LogFmt.info(log, LogDomain.PAYMENT, EventType.DOMAIN_EVENT_RETRY_PUBLISHED,
                () -> "orderId=" + paymentEvent.getOrderId()
                        + " retryCount=" + paymentEvent.getRetryCount()
                        + " " + previousStatus + "->" + paymentEvent.getStatus());
    }
}
