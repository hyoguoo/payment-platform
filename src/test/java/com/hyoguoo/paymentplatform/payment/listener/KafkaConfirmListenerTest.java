package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;

@DisplayName("KafkaConfirmListener 테스트")
class KafkaConfirmListenerTest {

    private KafkaConfirmListener kafkaConfirmListener;

    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;

    private static final String ORDER_ID = "order-123";
    private static final String PAYMENT_KEY = "payment-key-123";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 12, 0, 0);

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);

        kafkaConfirmListener = new KafkaConfirmListener(
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockTransactionCoordinator
        );
    }

    @Test
    @DisplayName("consume() - 정상 흐름: executePaymentSuccessCompletion()을 1회 호출한다")
    void consume_Success_CallsExecutePaymentSuccessCompletion_Once() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                any(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.consume(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletion(any(), any(PaymentEvent.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("consume() - 정상 흐름: executePaymentFailureCompensation()을 호출하지 않는다")
    void consume_Success_DoesNotCallFailureCompensation() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                any(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.consume(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("consume() - PaymentTossNonRetryableException 발생 시 executePaymentFailureCompensation()을 1회 호출한다")
    void consume_WhenNonRetryable_CallsFailureCompensation() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        willThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR))
                .given(mockPaymentCommandUseCase).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
        given(mockTransactionCoordinator.executePaymentFailureCompensation(
                any(), any(PaymentEvent.class), any(), any()))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.consume(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensation(any(), any(PaymentEvent.class), any(), any());
    }

    @Test
    @DisplayName("consume() - PaymentTossRetryableException 발생 시 예외가 re-throw된다 (@RetryableTopic이 처리)")
    void consume_WhenRetryable_ThrowsException() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        willThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR))
                .given(mockPaymentCommandUseCase).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));

        // when & then
        assertThatThrownBy(() -> kafkaConfirmListener.consume(ORDER_ID))
                .isInstanceOf(PaymentTossRetryableException.class);
    }

    @Test
    @DisplayName("consume() - 정상 흐름: validateCompletionStatus()가 confirmPaymentWithGateway() 전에 호출된다")
    void consume_Success_CallsValidateBeforeGateway() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                any(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.consume(ORDER_ID);

        // then
        var inOrder = Mockito.inOrder(mockPaymentCommandUseCase);
        inOrder.verify(mockPaymentCommandUseCase).validateCompletionStatus(
                any(PaymentEvent.class), any(PaymentConfirmCommand.class));
        inOrder.verify(mockPaymentCommandUseCase).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
    }

    @Test
    @DisplayName("consume() - validateCompletionStatus() PaymentValidException 발생 시 confirmPaymentWithGateway()를 호출하지 않고 예외가 전파된다")
    void consume_WhenValidateThrowsPaymentValidException_DoesNotCallGateway() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        willThrow(PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT))
                .given(mockPaymentCommandUseCase)
                .validateCompletionStatus(any(PaymentEvent.class), any(PaymentConfirmCommand.class));

        // when & then
        assertThatThrownBy(() -> kafkaConfirmListener.consume(ORDER_ID))
                .isInstanceOf(PaymentValidException.class);
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("consume() - validateCompletionStatus() PaymentStatusException 발생 시 confirmPaymentWithGateway()를 호출하지 않고 예외가 전파된다")
    void consume_WhenValidateThrowsPaymentStatusException_DoesNotCallGateway() throws Exception {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        willThrow(PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_COMPLETE))
                .given(mockPaymentCommandUseCase)
                .validateCompletionStatus(any(PaymentEvent.class), any(PaymentConfirmCommand.class));

        // when & then
        assertThatThrownBy(() -> kafkaConfirmListener.consume(ORDER_ID))
                .isInstanceOf(PaymentStatusException.class);
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("handleDlt() - DLT 도달 시 executePaymentFailureCompensation()을 1회 호출한다")
    void handleDlt_CallsExecutePaymentFailureCompensation_Once() {
        // given
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockTransactionCoordinator.executePaymentFailureCompensation(
                any(), any(PaymentEvent.class), any(), any()))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.handleDlt(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensation(any(), any(PaymentEvent.class), any(), any());
    }

    @Test
    @DisplayName("@RetryableTopic의 attempts가 \"6\"이다")
    void retryableTopic_HasAttempts6() throws NoSuchMethodException {
        // given
        RetryableTopic annotation = KafkaConfirmListener.class
                .getMethod("consume", String.class)
                .getAnnotation(RetryableTopic.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.attempts()).isEqualTo("6");
    }

    @Test
    @DisplayName("@RetryableTopic의 dltTopicSuffix가 \"-dlq\"이다")
    void retryableTopic_HasDltSuffix_Dlq() throws NoSuchMethodException {
        // given
        RetryableTopic annotation = KafkaConfirmListener.class
                .getMethod("consume", String.class)
                .getAnnotation(RetryableTopic.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.dltTopicSuffix()).isEqualTo("-dlq");
    }

    @Test
    @DisplayName("@KafkaListener의 topics가 \"payment-confirm\"이다")
    void kafkaListener_TopicIsPaymentConfirm() throws NoSuchMethodException {
        // given
        KafkaListener annotation = KafkaConfirmListener.class
                .getMethod("consume", String.class)
                .getAnnotation(KafkaListener.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.topics()).contains("payment-confirm");
    }

    @Test
    @DisplayName("@RetryableTopic의 include에 PaymentTossRetryableException만 포함된다")
    void retryableTopic_IncludesOnlyPaymentTossRetryableException() throws NoSuchMethodException {
        // given
        RetryableTopic annotation = KafkaConfirmListener.class
                .getMethod("consume", String.class)
                .getAnnotation(RetryableTopic.class);

        // then
        assertThat(annotation.include()).containsExactly(PaymentTossRetryableException.class);
        assertThat(annotation.exclude()).isEmpty();
    }

    private PaymentEvent createPaymentEvent(String orderId, PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .paymentKey(PAYMENT_KEY)
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentGatewayInfo createGatewayInfo(LocalDateTime approvedAt) {
        return PaymentGatewayInfo.builder()
                .paymentKey(PAYMENT_KEY)
                .orderId(ORDER_ID)
                .paymentDetails(
                        PaymentDetails.builder()
                                .approvedAt(approvedAt)
                                .totalAmount(BigDecimal.valueOf(15000))
                                .build()
                )
                .build();
    }
}
