package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEventType;
import com.hyoguoo.paymentplatform.payment.listener.port.PaymentHistoryService;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@DisplayName("PaymentHistoryEventListener 테스트")
class PaymentHistoryEventListenerTest {

    private PaymentHistoryService mockHistoryService;
    private PaymentHistoryEventListener listener;

    @BeforeEach
    void setUp() {
        mockHistoryService = Mockito.mock(PaymentHistoryService.class);
        listener = new PaymentHistoryEventListener(mockHistoryService);
    }

    @Test
    @DisplayName("onPaymentStatusChange - BEFORE_COMMIT 단계에서 payment_history insert를 1회 호출한다")
    void onPaymentStatusChange_InsertsHistoryBeforeCommit() throws NoSuchMethodException {
        // given
        Method method = PaymentHistoryEventListener.class.getMethod(
                "handlePaymentHistoryEvent", PaymentHistoryEvent.class);

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then - BEFORE_COMMIT phase 검증
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.BEFORE_COMMIT);

        // and - 실제 호출 시 recordPaymentHistory가 1회 호출됨을 검증
        PaymentHistoryEvent event = createPaymentHistoryEvent();
        listener.handlePaymentHistoryEvent(event);

        then(mockHistoryService).should(times(1)).recordPaymentHistory(event);
    }

    private PaymentHistoryEvent createPaymentHistoryEvent() {
        return new PaymentHistoryEvent(
                1L,
                "order-123",
                PaymentEventStatus.READY,
                PaymentEventStatus.IN_PROGRESS,
                null,
                LocalDateTime.now()
        ) {
            @Override
            public PaymentHistoryEventType getEventType() {
                return PaymentHistoryEventType.STATUS_CHANGE;
            }
        };
    }
}
