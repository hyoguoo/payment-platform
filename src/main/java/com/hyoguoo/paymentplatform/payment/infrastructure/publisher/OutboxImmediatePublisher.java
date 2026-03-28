package com.hyoguoo.paymentplatform.payment.infrastructure.publisher;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.payment.async-strategy", havingValue = "outbox")
public class OutboxImmediatePublisher implements PaymentConfirmPublisherPort {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(String orderId) {
        applicationEventPublisher.publishEvent(PaymentConfirmEvent.of(orderId));
    }
}
