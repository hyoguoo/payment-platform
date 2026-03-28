package com.hyoguoo.paymentplatform.payment.infrastructure.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
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
        outboxImmediatePublisher.publish("order-123");

        // then
        then(mockApplicationEventPublisher).should(times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("publish() 호출 시 PaymentConfirmEvent에 orderId가 담겨 발행된다")
    void publish_PaymentConfirmEvent에_orderId가_담겨_발행된다() {
        // given
        String orderId = "order-456";
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);

        // when
        outboxImmediatePublisher.publish(orderId);

        // then
        then(mockApplicationEventPublisher).should(times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PaymentConfirmEvent.class);
        assertThat(((PaymentConfirmEvent) captor.getValue()).getOrderId()).isEqualTo(orderId);
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
