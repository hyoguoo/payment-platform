package com.hyoguoo.paymentplatform.payment.infrastructure.kafka;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaConfirmPublisher implements PaymentConfirmPublisherPort {

    private static final String TOPIC = "payment-confirm";

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    public void publish(String orderId) {
        kafkaTemplate.send(TOPIC, orderId, orderId);
    }
}
