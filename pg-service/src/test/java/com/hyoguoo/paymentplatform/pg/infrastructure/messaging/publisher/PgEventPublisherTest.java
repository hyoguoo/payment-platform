package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayload;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PgEventPublisher 동기 발행 회귀 테스트.
 *
 * <p>불변식: Kafka 발행 실패 시 호출자 스레드로 예외가 반드시 전파되어야 한다.
 * payment-service b0cb6540 fire-and-forget 버그와 동일 구조 방지 — whenComplete 콜백에서
 * 예외를 삼키면 PgOutboxRelayService.relay()가 publish 실패에도 processed_at 을 갱신해 버린다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PgEventPublisher 동기 발행 계약")
class PgEventPublisherTest {

    private static final String EVENTS_CONFIRMED = "payment.events.confirmed";
    private static final String COMMANDS_CONFIRM = "payment.commands.confirm";
    private static final String COMMANDS_CONFIRM_DLQ = "payment.commands.confirm.dlq";

    @Mock
    private KafkaTemplate<String, ConfirmedEventPayload> confirmedEventKafkaTemplate;
    @Mock
    private KafkaTemplate<String, PgConfirmCommand> commandsConfirmKafkaTemplate;
    @Mock
    private KafkaTemplate<String, PgConfirmCommand> commandsConfirmDlqKafkaTemplate;

    private PgEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PgEventPublisher(
                new ObjectMapper(),
                confirmedEventKafkaTemplate,
                commandsConfirmKafkaTemplate,
                commandsConfirmDlqKafkaTemplate,
                EVENTS_CONFIRMED,
                COMMANDS_CONFIRM,
                COMMANDS_CONFIRM_DLQ
        );
        ReflectionTestUtils.setField(publisher, "sendTimeoutMillis", 500L);
    }

    @Test
    @DisplayName("publish: events.confirmed 토픽으로 ConfirmedEventPayload 가 발행된다")
    void publish_EventsConfirmed_SerializesAndSends() {
        String payloadJson = "{\"orderId\":\"order-1\",\"status\":\"APPROVED\",\"eventUuid\":\"uuid-1\"}";
        CompletableFuture<SendResult<String, ConfirmedEventPayload>> future = new CompletableFuture<>();
        future.complete(null);
        when(confirmedEventKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        publisher.publish(EVENTS_CONFIRMED, "order-1", payloadJson, Map.of());
    }

    @Test
    @DisplayName("publish: Kafka 브로커 실패 시 호출자 스레드로 예외가 동기 전파된다 (fire-and-forget 금지 불변)")
    void publish_WhenBrokerFails_PropagatesExceptionSynchronously() {
        String payloadJson = "{\"orderId\":\"order-1\",\"status\":\"APPROVED\",\"eventUuid\":\"uuid-1\"}";
        CompletableFuture<SendResult<String, ConfirmedEventPayload>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        when(confirmedEventKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        assertThatThrownBy(() -> publisher.publish(EVENTS_CONFIRMED, "order-1", payloadJson, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka 발행 실패")
                .hasRootCauseMessage("broker down");
    }

    @Test
    @DisplayName("publish: 브로커 응답이 타임아웃을 넘기면 TimeoutException 이 IllegalStateException 으로 감싸져 전파된다")
    void publish_WhenBrokerSlow_ThrowsTimeoutWrapped() {
        String payloadJson = "{\"orderId\":\"order-1\",\"status\":\"APPROVED\",\"eventUuid\":\"uuid-1\"}";
        // future 미완료 → .get(timeout) 이 TimeoutException 을 던진다
        CompletableFuture<SendResult<String, ConfirmedEventPayload>> future = new CompletableFuture<>();
        when(confirmedEventKafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> publisher.publish(EVENTS_CONFIRMED, "order-1", payloadJson, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("타임아웃");
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(400L);
    }

    @Test
    @DisplayName("publish: 알 수 없는 토픽은 IllegalArgumentException 을 던진다")
    void publish_UnknownTopic_ThrowsIllegalArgument() {
        assertThatThrownBy(() -> publisher.publish("unknown.topic", "k", "{}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Kafka topic");
    }
}
