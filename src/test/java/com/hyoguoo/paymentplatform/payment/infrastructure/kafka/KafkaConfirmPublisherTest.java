package com.hyoguoo.paymentplatform.payment.infrastructure.kafka;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

@DisplayName("KafkaConfirmPublisher 테스트")
class KafkaConfirmPublisherTest {

    private KafkaConfirmPublisher kafkaConfirmPublisher;

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockKafkaTemplate = Mockito.mock(KafkaTemplate.class);

    @BeforeEach
    void setUp() {
        mockKafkaTemplate = Mockito.mock(KafkaTemplate.class);
        kafkaConfirmPublisher = new KafkaConfirmPublisher(mockKafkaTemplate);
    }

    @Nested
    @DisplayName("publish() 메서드 테스트")
    class PublishTest {

        @Test
        @DisplayName("publish(orderId) 호출 시 kafkaTemplate.send(\"payment-confirm\", orderId, orderId)를 1회 호출한다")
        void publish_CallsKafkaTemplateSend_Once() {
            // given
            String orderId = "order-123";

            // when
            kafkaConfirmPublisher.publish(orderId);

            // then
            then(mockKafkaTemplate).should(times(1))
                    .send("payment-confirm", orderId, orderId);
        }
    }
}
