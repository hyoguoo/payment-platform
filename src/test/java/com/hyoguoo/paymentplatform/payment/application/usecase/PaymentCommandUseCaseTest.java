package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentCommandUseCaseTest {

    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentEventRepository mockPaymentEventRepository;
    private PaymentGatewayPort mockPaymentGatewayPort;
    private TestLocalDateTimeProvider testLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        mockPaymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        mockPaymentGatewayPort = Mockito.mock(PaymentGatewayPort.class);
        testLocalDateTimeProvider = new TestLocalDateTimeProvider();
        paymentCommandUseCase = new PaymentCommandUseCase(
                mockPaymentEventRepository,
                mockPaymentGatewayPort,
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
        PaymentEvent result = paymentCommandUseCase.executePayment(paymentEvent, paymentConfirmCommand.getPaymentKey());

        // then
        verify(paymentEvent, times(1)).execute(paymentKey, testLocalDateTimeProvider.now(), testLocalDateTimeProvider.now());
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
        PaymentEvent result = paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);

        // then
        verify(paymentEvent, times(1)).done(approvedAt, testLocalDateTimeProvider.now());
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());

    }

    @Test
    @DisplayName("결제 실패 처리를 호출하고 성공적으로 실패된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsFail() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String failureReason = "";

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);

        // then
        verify(paymentEvent, times(1)).fail(failureReason, testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 상태를 알 수 없음으로 처리하고 PaymentEvent를 반환한다.")
    void testMarkPaymentAsUnknown() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String reason = "";  // 빈 문자열로 테스트

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsUnknown(paymentEvent, reason);

        // then
        verify(paymentEvent, times(1)).unknown(reason, testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 상태 확인 시 정보를 조회하고 PaymentEvent의 상태를 확인 메서드를 호출한다.")
    void testValidateCompletionStatus_Success() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .build();

        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult statusResult =
                new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult(
                        "paymentKey",
                        "order123",
                        com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus.DONE,
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        null
                );

        // when
        when(mockPaymentGatewayPort.getStatus(paymentConfirmCommand.getPaymentKey()))
                .thenReturn(statusResult);
        paymentCommandUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        // then
        verify(paymentEvent, times(1))
                .validateCompletionStatus(any(PaymentConfirmCommand.class), any(PaymentGatewayInfo.class));
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

        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult confirmResult =
                new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult(
                        PaymentConfirmResultStatus.SUCCESS,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        null
                );

        // when
        when(mockPaymentGatewayPort.confirm(any(com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        PaymentGatewayInfo result = paymentCommandUseCase.confirmPaymentWithGateway(
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

        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult confirmResult =
                new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult(
                        PaymentConfirmResultStatus.RETRYABLE_FAILURE,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo("ERROR", "Retryable error", true)
                );

        // when & then
        when(mockPaymentGatewayPort.confirm(any(com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        assertThatThrownBy(
                () -> paymentCommandUseCase.confirmPaymentWithGateway(paymentConfirmCommand))
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

        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult confirmResult =
                new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult(
                        PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo("ERROR", "Non-retryable error", false)
                );

        // when & then
        when(mockPaymentGatewayPort.confirm(any(com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        assertThatThrownBy(
                () -> paymentCommandUseCase.confirmPaymentWithGateway(paymentConfirmCommand))
                .isInstanceOf(PaymentTossNonRetryableException.class);
    }

    @Test
    @DisplayName("결제 상태를 알 수 UNKNOWN 상태로 변경하고 PaymentEvent의 재시도 횟수를 증가시킨다.")
    void testIncreaseRetryCount() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        paymentCommandUseCase.increaseRetryCount(paymentEvent);

        // then
        verify(paymentEvent, times(1)).increaseRetryCount();
    }

    @Test
    @DisplayName("결제 만료 처리를 호출하고 성공적으로 만료된 PaymentEvent를 반환한다.")
    void testExpirePayment() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        PaymentEvent result = paymentCommandUseCase.expirePayment(paymentEvent);

        // then
        then(paymentEvent).should(times(1)).expire(testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 상태 확인 시 재시도 가능한 실패인 경우 RETRYABLE_FAILURE로 PaymentGatewayInfo를 구성한다.")
    void testValidateCompletionStatus_RetryableFailure() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .build();
        PaymentStatusResult statusResult = new PaymentStatusResult(
                "paymentKey",
                "order123",
                PaymentStatus.IN_PROGRESS,
                new BigDecimal(10000),
                null,
                new PaymentFailureInfo("PROVIDER_ERROR", "일시적 오류", true)
        );
        given(mockPaymentGatewayPort.getStatus(paymentConfirmCommand.getPaymentKey()))
                .willReturn(statusResult);

        // when
        paymentCommandUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        // then
        then(paymentEvent).should(times(1))
                .validateCompletionStatus(
                        any(PaymentConfirmCommand.class),
                        any(PaymentGatewayInfo.class)
                );
    }

    @Test
    @DisplayName("결제 상태 확인 시 재시도 불가능한 실패인 경우 NON_RETRYABLE_FAILURE로 PaymentGatewayInfo를 구성한다.")
    void testValidateCompletionStatus_NonRetryableFailure() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .build();
        PaymentStatusResult statusResult = new PaymentStatusResult(
                "paymentKey",
                "order123",
                PaymentStatus.IN_PROGRESS,
                new BigDecimal(10000),
                null,
                new PaymentFailureInfo("INVALID_REQUEST", "잘못된 요청", false)
        );
        given(mockPaymentGatewayPort.getStatus(paymentConfirmCommand.getPaymentKey()))
                .willReturn(statusResult);

        // when
        paymentCommandUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        // then
        then(paymentEvent).should(times(1))
                .validateCompletionStatus(
                        any(PaymentConfirmCommand.class),
                        any(PaymentGatewayInfo.class)
                );
    }

    @Test
    @DisplayName("결제 상태 확인 시 failure가 null이고 DONE이 아닌 경우 NON_RETRYABLE_FAILURE로 PaymentGatewayInfo를 구성한다.")
    void testValidateCompletionStatus_NullFailureNonDone() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .build();
        PaymentStatusResult statusResult = new PaymentStatusResult(
                "paymentKey",
                "order123",
                PaymentStatus.IN_PROGRESS,
                new BigDecimal(10000),
                null,
                null
        );
        given(mockPaymentGatewayPort.getStatus(paymentConfirmCommand.getPaymentKey()))
                .willReturn(statusResult);

        // when
        paymentCommandUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        // then
        then(paymentEvent).should(times(1))
                .validateCompletionStatus(
                        any(PaymentConfirmCommand.class),
                        any(PaymentGatewayInfo.class)
                );
    }
}
