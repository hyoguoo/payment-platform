package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
/**
 * KafkaMessagePublisher 단위 테스트.
 * T-J1: stock publishing은 StockOutboxKafkaPublisher로 분리되어 이 테스트에서 제거되었다.
 * 이 클래스는 payment.commands.confirm 토픽 단일 경로만 검증한다.
 *
 * <p>K6: sendTimeoutMillis 생성자 파라미터로 이전 — ReflectionTestUtils 제거.
 */
@ExtendWith(MockitoExtension.class)
class KafkaMessagePublisherTest {

    private static final String COMMANDS_CONFIRM = "payment.commands.confirm";

    @Mock
    private KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate;

    private KafkaMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaMessagePublisher(
                commandsConfirmKafkaTemplate,
                COMMANDS_CONFIRM,
                500L);
    }

    @Test
    void send_성공하면_예외없이_반환한다() {
        PaymentConfirmCommandMessage payload = new PaymentConfirmCommandMessage(
                "order-1", "pk-1", BigDecimal.valueOf(10000), PaymentGatewayType.TOSS, "evt-1");
        CompletableFuture<SendResult<String, PaymentConfirmCommandMessage>> future = new CompletableFuture<>();
        future.complete(null);
        when(commandsConfirmKafkaTemplate.send(eq(COMMANDS_CONFIRM), eq("order-1"), any()))
                .thenReturn(future);

        publisher.send(COMMANDS_CONFIRM, "order-1", payload);
    }

    @Test
    void 브로커_실패시_호출자_스레드로_예외를_전파한다() {
        PaymentConfirmCommandMessage payload = new PaymentConfirmCommandMessage(
                "order-1", "pk-1", BigDecimal.valueOf(10000), PaymentGatewayType.TOSS, "evt-1");
        CompletableFuture<SendResult<String, PaymentConfirmCommandMessage>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        when(commandsConfirmKafkaTemplate.send(eq(COMMANDS_CONFIRM), eq("order-1"), any()))
                .thenReturn(future);

        assertThatThrownBy(() -> publisher.send(COMMANDS_CONFIRM, "order-1", payload))
                .hasRootCauseMessage("broker down");
    }

    @Test
    void 브로커_응답_지연시_타임아웃_예외를_던진다() {
        PaymentConfirmCommandMessage payload = new PaymentConfirmCommandMessage(
                "order-1", "pk-1", BigDecimal.valueOf(10000), PaymentGatewayType.TOSS, "evt-1");
        CompletableFuture<SendResult<String, PaymentConfirmCommandMessage>> future = new CompletableFuture<>();
        when(commandsConfirmKafkaTemplate.send(eq(COMMANDS_CONFIRM), eq("order-1"), any()))
                .thenReturn(future);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> publisher.send(COMMANDS_CONFIRM, "order-1", payload))
                .isInstanceOf(RuntimeException.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(400L);
    }

    @Test
    void 알_수_없는_토픽은_IllegalArgumentException_을_던진다() {
        assertThatThrownBy(() -> publisher.send("unknown.topic", "k", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Kafka topic");
    }

    @Test
    void 토픽과_페이로드_타입이_일치하지_않으면_IllegalArgumentException_을_던진다() {
        // commands.confirm 토픽에 String 을 넘기면 거부된다 (cast 검사)
        assertThatThrownBy(() -> publisher.send(COMMANDS_CONFIRM, "k", "wrong-payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload 타입 불일치");
    }
}
