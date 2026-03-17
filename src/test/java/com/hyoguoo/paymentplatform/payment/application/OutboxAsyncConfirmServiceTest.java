package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@DisplayName("OutboxAsyncConfirmService 테스트")
class OutboxAsyncConfirmServiceTest {

    private OutboxAsyncConfirmService outboxAsyncConfirmService;

    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentFailureUseCase mockPaymentFailureUseCase;

    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockPaymentFailureUseCase = Mockito.mock(PaymentFailureUseCase.class);

        outboxAsyncConfirmService = new OutboxAsyncConfirmService(
                mockTransactionCoordinator,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockPaymentFailureUseCase
        );
    }

    @Nested
    @DisplayName("confirm() 메서드 테스트")
    class ConfirmTest {

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
            outboxAsyncConfirmService.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executeStockDecreaseWithOutboxCreation(orderId, paymentEvent.getPaymentOrderList());
        }

        @Test
        @DisplayName("confirm() 결과의 responseType은 ASYNC_202다")
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
            PaymentConfirmAsyncResult result = outboxAsyncConfirmService.confirm(command);

            // then
            assertThat(result.getResponseType()).isEqualTo(ResponseType.ASYNC_202);
        }

        @Test
        @DisplayName("재고 부족 시 PaymentFailureUseCase.handleStockFailure()를 호출하고 예외를 전파한다")
        void confirm_WhenStockInsufficient_CallsHandleStockFailure() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId)
                    .paymentKey("payment-key")
                    .amount(BigDecimal.valueOf(10000))
                    .build();

            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            given(mockTransactionCoordinator.executeStockDecreaseWithOutboxCreation(anyString(), anyList()))
                    .willThrow(PaymentOrderedProductStockException.of(
                            PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH));

            // when & then
            assertThatThrownBy(() -> outboxAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(mockPaymentFailureUseCase).should(times(1))
                    .handleStockFailure(eq(inProgressEvent), anyString());
        }

        @Test
        @DisplayName("OutboxAsyncConfirmService는 @ConditionalOnProperty(havingValue=outbox, matchIfMissing=false)를 가진다")
        void outboxAsyncConfirmService_HasConditionalOnPropertyAnnotation() {
            // given
            ConditionalOnProperty annotation = OutboxAsyncConfirmService.class.getAnnotation(ConditionalOnProperty.class);

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
