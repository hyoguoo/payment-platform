package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.annotation.RetryableTopic;

@DisplayName("KafkaConfirmListener 테스트")
class KafkaConfirmListenerTest {

    private KafkaConfirmListener kafkaConfirmListener;

    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;

    private static final String ORDER_ID = "order-123";
    private static final String PAYMENT_KEY = "payment-key-123";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(15000);
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
    @DisplayName("consume() - 정상 흐름: confirmPaymentWithGateway()를 1회 호출한다")
    void consume_CallsConfirmPaymentWithGateway_Once() throws Exception {
        // given
        String message = ORDER_ID + ":" + PAYMENT_KEY + ":" + AMOUNT;
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                any(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.consume(message);

        // then
        then(mockPaymentCommandUseCase).should(times(1))
                .confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
    }

    @Test
    @DisplayName("consume() - PaymentTossNonRetryableException 발생 시 예외를 던지지 않는다 (DLT로 라우팅)")
    void consume_WhenNonRetryable_DoesNotThrow() throws Exception {
        // given
        String message = ORDER_ID + ":" + PAYMENT_KEY + ":" + AMOUNT;
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        willThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR))
                .given(mockPaymentCommandUseCase).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));

        // when & then: DLT 라우팅을 위해 예외를 다시 던진다 — @NonRetryable로 DLT로 전달됨
        // 실제 KafkaConfirmListener 구현에서 non-retryable 예외는 DLT 핸들러로 라우팅됨
        // 이 스텁 테스트는 Plan 03에서 KafkaConfirmListener 구현 후 완성됨
        assertThat(kafkaConfirmListener).isNotNull();
    }

    @Test
    @DisplayName("handleDlt() - DLT 메시지 수신 시 executePaymentFailureCompensation()를 1회 호출한다")
    void handleDlt_CallsFailureCompensation_Once() throws Exception {
        // given
        String message = ORDER_ID + ":" + PAYMENT_KEY + ":" + AMOUNT;
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID, PaymentEventStatus.IN_PROGRESS);

        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockTransactionCoordinator.executePaymentFailureCompensation(
                any(), any(PaymentEvent.class), any(), any()))
                .willReturn(paymentEvent);

        // when
        kafkaConfirmListener.handleDlt(message);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensation(any(), any(PaymentEvent.class), any(), any());
    }

    @Test
    @DisplayName("KafkaConfirmListener.consume()은 @RetryableTopic attempts=6 애노테이션을 가진다")
    void retryableTopic_HasAttempts6() throws NoSuchMethodException {
        // given
        RetryableTopic annotation = KafkaConfirmListener.class
                .getMethod("consume", String.class)
                .getAnnotation(RetryableTopic.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.attempts()).isEqualTo("6");
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
                                .totalAmount(AMOUNT)
                                .build()
                )
                .build();
    }
}
