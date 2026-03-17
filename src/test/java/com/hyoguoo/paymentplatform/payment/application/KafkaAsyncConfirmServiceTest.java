package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@DisplayName("KafkaAsyncConfirmService 테스트")
class KafkaAsyncConfirmServiceTest {

    private KafkaAsyncConfirmService kafkaAsyncConfirmService;

    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private FakePaymentConfirmPublisher fakeConfirmPublisher;
    private PaymentFailureUseCase mockPaymentFailureUseCase;

    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        fakeConfirmPublisher = new FakePaymentConfirmPublisher();
        mockPaymentFailureUseCase = Mockito.mock(PaymentFailureUseCase.class);

        kafkaAsyncConfirmService = new KafkaAsyncConfirmService(
                mockTransactionCoordinator,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                fakeConfirmPublisher,
                mockPaymentFailureUseCase
        );
    }

    @Nested
    @DisplayName("confirm() 메서드 테스트")
    class ConfirmTest {

        @Test
        @DisplayName("confirm() 호출 시 confirmPublisher.publish(orderId)를 1회 호출한다")
        void confirm_CallsConfirmPublisher_Once() throws PaymentOrderedProductStockException {
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

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);

            // when
            kafkaAsyncConfirmService.confirm(command);

            // then
            assertThat(fakeConfirmPublisher.getPublishedOrderIds()).hasSize(1);
            assertThat(fakeConfirmPublisher.getPublishedOrderIds().getFirst()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("confirm() 호출 시 transactionCoordinator.executeStockDecreaseOnly()를 1회 호출한다")
        void confirm_CallsExecuteStockDecreaseOnly_Once() throws PaymentOrderedProductStockException {
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

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);

            // when
            kafkaAsyncConfirmService.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executeStockDecreaseOnly(orderId, paymentEvent.getPaymentOrderList());
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

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);

            // when
            PaymentConfirmAsyncResult result = kafkaAsyncConfirmService.confirm(command);

            // then
            assertThat(result.getResponseType()).isEqualTo(ResponseType.ASYNC_202);
        }

        @Test
        @DisplayName("재고 부족 시 PaymentFailureUseCase.handleStockFailure()를 호출하고 예외를 전파한다")
        void confirm_WhenStockInsufficient_CallsHandleStockFailure() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId).paymentKey("key").amount(BigDecimal.valueOf(10000)).build();
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            willThrow(PaymentOrderedProductStockException.of(
                            PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH))
                    .given(mockTransactionCoordinator)
                    .executeStockDecreaseOnly(anyString(), anyList());

            // when & then
            assertThatThrownBy(() -> kafkaAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(mockPaymentFailureUseCase).should(times(1))
                    .handleStockFailure(eq(inProgressEvent), anyString());
            assertThat(fakeConfirmPublisher.getPublishedOrderIds()).isEmpty();
        }

        @Test
        @DisplayName("Kafka publish 실패 시 executePaymentFailureCompensation()을 호출하고 예외를 전파한다")
        void confirm_WhenPublishFails_CallsFailureCompensation() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .orderId(orderId).paymentKey("key").amount(BigDecimal.valueOf(10000)).build();
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);

            // publish 실패하는 구현 사용
            PaymentConfirmPublisherPort failingPublisher = orderId2 -> {
                throw new RuntimeException("Kafka unavailable");
            };
            kafkaAsyncConfirmService = new KafkaAsyncConfirmService(
                    mockTransactionCoordinator,
                    mockPaymentLoadUseCase,
                    mockPaymentCommandUseCase,
                    failingPublisher,
                    mockPaymentFailureUseCase
            );

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);
            given(mockTransactionCoordinator.executePaymentFailureCompensation(
                    anyString(), any(PaymentEvent.class), any(), anyString())).willReturn(inProgressEvent);

            // when & then
            assertThatThrownBy(() -> kafkaAsyncConfirmService.confirm(command))
                    .isInstanceOf(RuntimeException.class);

            then(mockTransactionCoordinator).should(times(1))
                    .executePaymentFailureCompensation(eq(orderId), eq(inProgressEvent), any(), anyString());
        }

        @Test
        @DisplayName("KafkaAsyncConfirmService는 @ConditionalOnProperty(havingValue=kafka, matchIfMissing=false)를 가진다")
        void kafkaAsyncConfirmService_HasConditionalOnPropertyAnnotation() {
            // given
            ConditionalOnProperty annotation = KafkaAsyncConfirmService.class.getAnnotation(ConditionalOnProperty.class);

            // then
            assertThat(annotation).isNotNull();
            assertThat(annotation.name()).contains("spring.payment.async-strategy");
            assertThat(annotation.havingValue()).isEqualTo("kafka");
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

    private static class FakePaymentConfirmPublisher implements PaymentConfirmPublisherPort {

        private final List<String> publishedOrderIds = new ArrayList<>();

        @Override
        public void publish(String orderId) {
            publishedOrderIds.add(orderId);
        }

        public List<String> getPublishedOrderIds() {
            return Collections.unmodifiableList(publishedOrderIds);
        }
    }
}
