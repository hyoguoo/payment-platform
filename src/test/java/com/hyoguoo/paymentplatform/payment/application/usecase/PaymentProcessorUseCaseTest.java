package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentProcessorUseCaseTest {

    private PaymentProcessorUseCase paymentProcessorUseCase;
    private PaymentEventRepository mockPaymentEventRepository;
    private PaymentGatewayHandler mockPaymentGatewayHandler;
    private LocalDateTimeProvider testLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        mockPaymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        mockPaymentGatewayHandler = Mockito.mock(PaymentGatewayHandler.class);
        testLocalDateTimeProvider = new TestLocalDateTimeProvider();
        paymentProcessorUseCase = new PaymentProcessorUseCase(
                mockPaymentEventRepository,
                mockPaymentGatewayHandler,
                testLocalDateTimeProvider
        );
    }

    @Test
    @DisplayName("결제 시작을 호출하고 성공적으로 처리된 PaymentEvent를 반환한다.")
    void testExecutePayment_Success() {
        // given
        String paymentKey = "paymentKey";
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey(paymentKey)
                .amount(new BigDecimal(10000))
                .build();

        // when
        when(mockPaymentEventRepository.findByOrderId(paymentConfirmCommand.getOrderId()))
                .thenReturn(Optional.of(paymentEvent));
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentProcessorUseCase.executePayment(paymentEvent, paymentConfirmCommand.getPaymentKey());

        // then
        verify(paymentEvent, times(1)).execute(paymentKey, testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 완료 처리를 호출하고 성공적으로 완료된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsDone() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentProcessorUseCase.markPaymentAsDone(paymentEvent, approvedAt);

        // then
        verify(paymentEvent, times(1)).done(approvedAt);
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());
    }

    @Test
    @DisplayName("결제 실패 처리를 호출하고 성공적으로 실패된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsFail() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentProcessorUseCase.markPaymentAsFail(paymentEvent);

        // then
        verify(paymentEvent, times(1)).fail();
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());
    }

    @Test
    @DisplayName("결제 상태를 알 수 없음으로 처리하고 PaymentEvent를 반환한다.")
    void testMarkPaymentAsUnknown() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentProcessorUseCase.markPaymentAsUnknown(paymentEvent);

        // then
        verify(paymentEvent, times(1)).unknown();
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());
    }

    @Test
    @DisplayName("결제 상태 확인 시 정보를 조회하고 PaymentEvent의 상태를 확인 메서드를 호출한다.")
    void testValidateCompletionStatus_Success() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .build();
        TossPaymentInfo tossPaymentInfo = Mockito.mock(TossPaymentInfo.class);

        // when
        when(mockPaymentGatewayHandler.getPaymentInfoByOrderId(paymentConfirmCommand.getOrderId()))
                .thenReturn(tossPaymentInfo);
        paymentProcessorUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        // then
        verify(paymentEvent, times(1))
                .validateCompletionStatus(paymentConfirmCommand, tossPaymentInfo);
    }

    @Test
    @DisplayName("Toss 결제 승인 성공 시 성공 결과와 함께 결제 정보를 반환한다.")
    void testConfirmPaymentWithGateway_Success() throws Exception {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .build();

        TossPaymentInfo tossPaymentInfo = TossPaymentInfo.builder()
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .build();

        // when
        when(mockPaymentGatewayHandler.confirmPayment(any(TossConfirmGatewayCommand.class)))
                .thenReturn(tossPaymentInfo);
        TossPaymentInfo result = paymentProcessorUseCase.confirmPaymentWithGateway(
                paymentConfirmCommand
        );

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("Toss 결제 승인 중 재시도 가능한 실패 시 재시도 가능 예외를 던진다.")
    void testConfirmPaymentWithGateway_RetryableFailure() {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .build();

        TossPaymentInfo tossPaymentInfo = TossPaymentInfo.builder()
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.RETRYABLE_FAILURE)
                .build();

        // when & then
        when(mockPaymentGatewayHandler.confirmPayment(any(TossConfirmGatewayCommand.class)))
                .thenReturn(tossPaymentInfo);
        assertThatThrownBy(
                () -> paymentProcessorUseCase.confirmPaymentWithGateway(paymentConfirmCommand))
                .isInstanceOf(PaymentTossRetryableException.class);
    }

    @Test
    @DisplayName("Toss 결제 승인 중 재시도 불가능한 실패 시 재시도 불가능 예외를 던진다.")
    void testConfirmPaymentWithGateway_NonRetryableFailure() {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .build();

        TossPaymentInfo tossPaymentInfo = TossPaymentInfo.builder()
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE)
                .build();

        // when & then
        when(mockPaymentGatewayHandler.confirmPayment(any(TossConfirmGatewayCommand.class)))
                .thenReturn(tossPaymentInfo);
        assertThatThrownBy(
                () -> paymentProcessorUseCase.confirmPaymentWithGateway(paymentConfirmCommand))
                .isInstanceOf(PaymentTossNonRetryableException.class);
    }
}
