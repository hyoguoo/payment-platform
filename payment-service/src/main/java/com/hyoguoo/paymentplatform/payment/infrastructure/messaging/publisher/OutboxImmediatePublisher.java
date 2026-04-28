package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxImmediatePublisher implements PaymentConfirmPublisherPort {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(String orderId, Long userId, BigDecimal amount, String paymentKey) {
        applicationEventPublisher.publishEvent(PaymentConfirmEvent.of(orderId, userId, amount, paymentKey));
    }
}
