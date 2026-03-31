package com.hyoguoo.paymentplatform.payment.infrastructure.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;

@DisplayName("OutboxImmediatePublisher 테스트")
class OutboxImmediatePublisherTest {

    private OutboxImmediatePublisher outboxImmediatePublisher;
    private ApplicationEventPublisher mockApplicationEventPublisher;

    @BeforeEach
    void setUp() {
        mockApplicationEventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        outboxImmediatePublisher = new OutboxImmediatePublisher(mockApplicationEventPublisher);
    }

    @Test
    @DisplayName("publish() 호출 시 ApplicationEventPublisher.publishEvent()를 1회 호출한다")
    void publish_ApplicationEventPublisher_publishEvent를_1회_호출한다() {
        // when
        outboxImmediatePublisher.publish("order-123", 1L, BigDecimal.valueOf(10000), "payment-key");

        // then
        then(mockApplicationEventPublisher).should(times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("publish() 호출 시 PaymentConfirmEvent에 모든 필드가 담겨 발행된다")
    void publish_PaymentConfirmEvent에_모든_필드가_담겨_발행된다() {
        // given
        String orderId = "order-456";
        Long userId = 1L;
        BigDecimal amount = BigDecimal.valueOf(15000);
        String paymentKey = "payment-key-123";
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        // when
        outboxImmediatePublisher.publish(orderId, userId, amount, paymentKey);

        // then
        then(mockApplicationEventPublisher).should(times(1)).publishEvent(captor.capture());
        PaymentConfirmEvent event = (PaymentConfirmEvent) captor.getValue();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getAmount()).isEqualByComparingTo(amount);
        assertThat(event.getPaymentKey()).isEqualTo(paymentKey);
    }

    @Test
    @DisplayName("OutboxImmediatePublisher는 @ConditionalOnProperty(havingValue=outbox)를 가진다")
    void outboxImmediatePublisher_ConditionalOnProperty_outbox_선언되어_있다() {
        // given
        ConditionalOnProperty annotation =
                OutboxImmediatePublisher.class.getAnnotation(ConditionalOnProperty.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains("spring.payment.async-strategy");
        assertThat(annotation.havingValue()).isEqualTo("outbox");
    }
}
