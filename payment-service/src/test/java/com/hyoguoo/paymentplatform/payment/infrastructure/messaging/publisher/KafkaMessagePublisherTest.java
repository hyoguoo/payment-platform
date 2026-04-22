package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class KafkaMessagePublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private KafkaMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaMessagePublisher(kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMillis", 500L);
    }

    @Test
    void send_성공하면_예외없이_반환한다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        when(kafkaTemplate.send(eq("topic-a"), eq("key-1"), any())).thenReturn(future);

        publisher.send("topic-a", "key-1", "payload");
    }

    @Test
    void 브로커_실패시_호출자_스레드로_예외를_전파한다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(eq("topic-a"), eq("key-1"), any())).thenReturn(future);

        assertThatThrownBy(() -> publisher.send("topic-a", "key-1", "payload"))
                .hasRootCauseMessage("broker down");
    }

    @Test
    void 브로커_응답_지연시_타임아웃_예외를_던진다() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(eq("topic-a"), eq("key-1"), any())).thenReturn(future);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> publisher.send("topic-a", "key-1", "payload"))
                .isInstanceOf(RuntimeException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(400L);
    }
}
