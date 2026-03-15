package com.hyoguoo.paymentplatform.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@DisplayName("OutboxConfirmAdapter 테스트")
class OutboxConfirmAdapterTest {

    private OutboxConfirmAdapter outboxConfirmAdapter;

    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;

    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);

        outboxConfirmAdapter = new OutboxConfirmAdapter(
                mockTransactionCoordinator,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase
        );
    }

    @Nested
    @DisplayName("confirm() 메서드 테스트")
    class ConfirmTest {

        @Test
        @DisplayName("confirm() 호출 시 paymentCommandUseCase.executePayment()를 1회 호출한다 (paymentKey 기록 필수)")
        void confirm_CallsExecutePayment_Once() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId)
                    .paymentKey(paymentKey)
                    .amount(amount)
                    .build();

            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.createPending(orderId);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            given(mockTransactionCoordinator.executeStockDecreaseWithOutboxCreation(anyString(), anyList())).willReturn(outbox);

            // when
            outboxConfirmAdapter.confirm(command);

            // then
            then(mockPaymentCommandUseCase).should(times(1))
                    .executePayment(paymentEvent, paymentKey);
        }

        @Test
        @DisplayName("confirm() 호출 시 transactionCoordinator.executeStockDecreaseWithOutboxCreation()를 1회 호출한다")
        void confirm_CallsExecuteStockDecreaseWithOutboxCreation_Once() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId)
                    .paymentKey(paymentKey)
                    .amount(amount)
                    .build();

            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.createPending(orderId);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            given(mockTransactionCoordinator.executeStockDecreaseWithOutboxCreation(anyString(), anyList())).willReturn(outbox);

            // when
            outboxConfirmAdapter.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executeStockDecreaseWithOutboxCreation(orderId, paymentEvent.getPaymentOrderList());
        }

        @Test
        @DisplayName("confirm() 결과의 responseType은 ASYNC_202, orderId와 amount가 command와 동일하다")
        void confirm_Returns_Async202() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId)
                    .paymentKey(paymentKey)
                    .amount(amount)
                    .build();

            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.createPending(orderId);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            given(mockTransactionCoordinator.executeStockDecreaseWithOutboxCreation(anyString(), anyList())).willReturn(outbox);

            // when
            PaymentConfirmAsyncResult result = outboxConfirmAdapter.confirm(command);

            // then
            assertThat(result.getResponseType()).isEqualTo(ResponseType.ASYNC_202);
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getAmount()).isEqualTo(amount);
        }

        @Test
        @DisplayName("OutboxConfirmAdapter는 @ConditionalOnProperty(name=spring.payment.async-strategy, havingValue=outbox, matchIfMissing=false)를 가진다")
        void outboxConfirmAdapter_HasConditionalOnPropertyAnnotation() {
            // given
            ConditionalOnProperty annotation = OutboxConfirmAdapter.class.getAnnotation(ConditionalOnProperty.class);

            // then
            assertThat(annotation).isNotNull();
            assertThat(annotation.name()).contains("spring.payment.async-strategy");
            assertThat(annotation.havingValue()).isEqualTo("outbox");
            assertThat(annotation.matchIfMissing()).isFalse();
        }
    }

    private PaymentEvent createPaymentEvent(String orderId, PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }
}
