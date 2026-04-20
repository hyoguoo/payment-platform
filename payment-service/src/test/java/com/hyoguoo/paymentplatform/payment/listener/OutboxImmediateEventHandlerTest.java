package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
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

    private PaymentConfirmChannel mockChannel;
    private OutboxImmediateEventHandler handler;

    @BeforeEach
    void setUp() {
        mockChannel = Mockito.mock(PaymentConfirmChannel.class);
        handler = new OutboxImmediateEventHandler(mockChannel);
    }

    private PaymentConfirmEvent createEvent(String orderId) {
        return PaymentConfirmEvent.of(orderId, 1L, BigDecimal.valueOf(15000), "payment-key-123");
    }

    @Test
    @DisplayName("handle - 채널 offer 성공: offer(orderId)를 호출한다")
    void handle_채널offer_성공_true반환() {
        // given
        PaymentConfirmEvent event = createEvent(ORDER_ID);
        given(mockChannel.offer(ORDER_ID)).willReturn(true);

        // when
        handler.handle(event);

        // then
        then(mockChannel).should(times(1)).offer(ORDER_ID);
    }

    @Test
    @DisplayName("handle - 채널 오버플로우: offer(orderId)를 호출한다")
    void handle_채널오버플로우_offer_false반환() {
        // given
        PaymentConfirmEvent event = createEvent(ORDER_ID);
        given(mockChannel.offer(ORDER_ID)).willReturn(false);

        // when
        handler.handle(event);

        // then
        then(mockChannel).should(times(1)).offer(ORDER_ID);
    }

    @Test
    @DisplayName("handle - @TransactionalEventListener(phase=AFTER_COMMIT) 애노테이션이 존재한다")
    void handle_TransactionalEventListener_AFTER_COMMIT_존재한다() throws NoSuchMethodException {
        // given
        Method method = OutboxImmediateEventHandler.class.getMethod("handle", PaymentConfirmEvent.class);

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("handle - @Async 애노테이션이 없다")
    void handle_Async_애노테이션_없다() throws NoSuchMethodException {
        // given
        Method method = OutboxImmediateEventHandler.class.getMethod("handle", PaymentConfirmEvent.class);

        // when & then
        assertThat(method.getAnnotation(Async.class)).isNull();
    }
}
