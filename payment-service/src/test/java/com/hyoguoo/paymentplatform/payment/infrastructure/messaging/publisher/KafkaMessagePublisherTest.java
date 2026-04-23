package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.PaymentConfirmCommandMessage;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockRestoreEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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

    private static final String COMMANDS_CONFIRM = "payment.commands.confirm";
    private static final String EVENTS_STOCK_COMMITTED = "payment.events.stock-committed";
    private static final String EVENTS_STOCK_RESTORE = "stock.events.restore";

    @Mock
    private KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate;
    @Mock
    private KafkaTemplate<String, StockCommittedEvent> stockCommittedKafkaTemplate;
    @Mock
    private KafkaTemplate<String, StockRestoreEvent> stockRestoreKafkaTemplate;

    private KafkaMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaMessagePublisher(
                commandsConfirmKafkaTemplate,
                stockCommittedKafkaTemplate,
                stockRestoreKafkaTemplate,
                COMMANDS_CONFIRM,
                EVENTS_STOCK_COMMITTED,
                EVENTS_STOCK_RESTORE);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMillis", 500L);
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
        // commands.confirm 토픽에 StockCommittedEvent 를 넘기면 거부된다 (cast 검사)
        StockCommittedEvent wrongPayload = new StockCommittedEvent(1L, 1, "idem", Instant.now());

        assertThatThrownBy(() -> publisher.send(COMMANDS_CONFIRM, "k", wrongPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload 타입 불일치");
    }

    @Test
    void stock_committed_토픽은_전용_템플릿으로_발행된다() {
        StockCommittedEvent payload = new StockCommittedEvent(42L, 3, "idem-1", Instant.now());
        CompletableFuture<SendResult<String, StockCommittedEvent>> future = new CompletableFuture<>();
        future.complete(null);
        when(stockCommittedKafkaTemplate.send(eq(EVENTS_STOCK_COMMITTED), eq("42"), any()))
                .thenReturn(future);

        publisher.send(EVENTS_STOCK_COMMITTED, "42", payload);
    }

    @Test
    void stock_restore_토픽은_전용_템플릿으로_발행된다() {
        StockRestoreEvent payload = new StockRestoreEvent(
                UUID.randomUUID(), "order-1", 42L, 3, Instant.now());
        CompletableFuture<SendResult<String, StockRestoreEvent>> future = new CompletableFuture<>();
        future.complete(null);
        when(stockRestoreKafkaTemplate.send(eq(EVENTS_STOCK_RESTORE), eq("42"), any()))
                .thenReturn(future);

        publisher.send(EVENTS_STOCK_RESTORE, "42", payload);
    }
}
