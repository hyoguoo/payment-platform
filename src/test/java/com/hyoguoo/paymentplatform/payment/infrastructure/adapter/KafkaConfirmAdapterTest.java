package com.hyoguoo.paymentplatform.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;

@DisplayName("KafkaConfirmAdapter 테스트")
class KafkaConfirmAdapterTest {

    private KafkaConfirmAdapter kafkaConfirmAdapter;

    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> mockKafkaTemplate = Mockito.mock(KafkaTemplate.class);

    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockKafkaTemplate = Mockito.mock(KafkaTemplate.class);

        kafkaConfirmAdapter = new KafkaConfirmAdapter(
                mockTransactionCoordinator,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockKafkaTemplate
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

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);

            // when
            kafkaConfirmAdapter.confirm(command);

            // then
            then(mockPaymentCommandUseCase).should(times(1))
                    .executePayment(paymentEvent, paymentKey);
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
            kafkaConfirmAdapter.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .executeStockDecreaseOnly(orderId, paymentEvent.getPaymentOrderList());
        }

        @Test
        @DisplayName("confirm() 호출 시 kafkaTemplate.send()를 payment-confirm-requests 토픽에 orderId로 1회 호출한다")
        void confirm_CallsKafkaTemplateSend_Once() throws PaymentOrderedProductStockException {
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
            kafkaConfirmAdapter.confirm(command);

            // then
            then(mockKafkaTemplate).should(times(1))
                    .send(eq("payment-confirm-requests"), eq(orderId), eq(orderId));
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

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockPaymentCommandUseCase.executePayment(any(PaymentEvent.class), anyString())).willReturn(inProgressEvent);

            // when
            PaymentConfirmAsyncResult result = kafkaConfirmAdapter.confirm(command);

            // then
            assertThat(result.getResponseType()).isEqualTo(ResponseType.ASYNC_202);
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getAmount()).isEqualTo(amount);
        }

        @Test
        @DisplayName("KafkaConfirmAdapter는 @ConditionalOnProperty(name=spring.payment.async-strategy, havingValue=kafka, matchIfMissing=false)를 가진다")
        void kafkaConfirmAdapter_HasConditionalOnPropertyAnnotation() {
            // given
            ConditionalOnProperty annotation = KafkaConfirmAdapter.class.getAnnotation(ConditionalOnProperty.class);

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
}
