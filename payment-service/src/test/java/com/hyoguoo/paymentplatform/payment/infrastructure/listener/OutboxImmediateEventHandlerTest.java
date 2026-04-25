package com.hyoguoo.paymentplatform.payment.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@DisplayName("OutboxImmediateEventHandler 테스트")
class OutboxImmediateEventHandlerTest {

    private static final String ORDER_ID = "order-123";

    private OutboxRelayService mockRelayService;
    private OutboxImmediateEventHandler handler;

    @BeforeEach
    void setUp() {
        mockRelayService = Mockito.mock(OutboxRelayService.class);
        handler = new OutboxImmediateEventHandler(mockRelayService);
    }

    private PaymentConfirmEvent createEvent(String orderId) {
        return PaymentConfirmEvent.of(orderId, 1L, BigDecimal.valueOf(15000), "payment-key-123");
    }

    @Test
    @DisplayName("handle - relayService.relay(orderId)를 1회 호출한다")
    void handle_relay_1회_호출() {
        PaymentConfirmEvent event = createEvent(ORDER_ID);

        handler.handle(event);

        then(mockRelayService).should(times(1)).relay(ORDER_ID);
    }

    @Test
    @DisplayName("handle - @TransactionalEventListener(phase=AFTER_COMMIT) 애노테이션이 존재한다")
    void handle_TransactionalEventListener_AFTER_COMMIT_존재한다() throws NoSuchMethodException {
        Method method = OutboxImmediateEventHandler.class.getMethod("handle", PaymentConfirmEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("handle - @Async(\"outboxRelayExecutor\") 애노테이션이 존재한다")
    void handle_Async_애노테이션_존재한다() throws NoSuchMethodException {
        Method method = OutboxImmediateEventHandler.class.getMethod("handle", PaymentConfirmEvent.class);

        Async annotation = method.getAnnotation(Async.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("outboxRelayExecutor");
    }
}
