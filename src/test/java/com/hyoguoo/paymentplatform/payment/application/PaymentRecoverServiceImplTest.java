package com.hyoguoo.paymentplatform.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentRecoveryUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentRecoverServiceImplTest {

    private PaymentRecoverServiceImpl paymentRecoverService;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentProcessorUseCase mockPaymentProcessorUseCase;
    private PaymentRecoveryUseCase mockPaymentRecoveryUseCase;
    private LocalDateTimeProvider mockLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentProcessorUseCase = Mockito.mock(PaymentProcessorUseCase.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);
        mockPaymentRecoveryUseCase = Mockito.mock(PaymentRecoveryUseCase.class);

        paymentRecoverService = new PaymentRecoverServiceImpl(
                mockPaymentLoadUseCase,
                mockPaymentProcessorUseCase,
                mockPaymentRecoveryUseCase,
                mockLocalDateTimeProvider
        );
    }

    @Test
    @DisplayName("재시도 가능한 결제를 성공적으로 처리한다.")
    void testRecoverRetryablePayment_Success() throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentEvent mockDonePaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);

        TossPaymentInfo mockTossPaymentInfo = Mockito.mock(TossPaymentInfo.class);
        TossPaymentDetails mockTossPaymentDetails = Mockito.mock(TossPaymentDetails.class);
        when(mockTossPaymentInfo.getPaymentDetails()).thenReturn(mockTossPaymentDetails);
        when(mockTossPaymentDetails.getApprovedAt()).thenReturn(LocalDateTime.now());

        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        when(mockPaymentProcessorUseCase.increaseRetryCount(any(PaymentEvent.class))).thenReturn(mockPaymentEvent);
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any())).thenReturn(mockTossPaymentInfo);
        when(mockPaymentProcessorUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .thenReturn(mockDonePaymentEvent);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).markPaymentAsDone(eq(mockPaymentEvent), any(LocalDateTime.class));
        verify(mockPaymentRecoveryUseCase, times(1))
                .markRecoverySuccess(eq(mockPaymentEvent), eq(mockDonePaymentEvent));
    }

    @Test
    @DisplayName("재시도 불가 시 복구 실패(NON_RETRYABLE_ERROR)로 기록한다.")
    void testRecoverRetryablePayment_ValidNonRetryableFailure() {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentEvent failedPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);

        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(false);
        when(mockPaymentProcessorUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class))).thenReturn(
                failedPaymentEvent);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(0)).increaseRetryCount(any(PaymentEvent.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentRecoveryUseCase, times(1))
                .markRecoveryFailure(eq(mockPaymentEvent), eq("NON_RETRYABLE_ERROR"), anyString());
    }

    @Test
    @DisplayName("재시도 가능 예외 시 복구 재시도 실패(RETRYABLE_ERROR)로 기록하고 재시도 횟수를 증가한다.")
    void testRecoverRetryablePayment_RetryableFailure()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);
        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        when(mockPaymentProcessorUseCase.increaseRetryCount(any(PaymentEvent.class))).thenReturn(mockPaymentEvent);
        doThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR))
                .when(mockPaymentProcessorUseCase).confirmPaymentWithGateway(any());

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class));
        verify(mockPaymentRecoveryUseCase, times(1))
                .markRecoveryRetryableFailure(eq(mockPaymentEvent), eq("RETRYABLE_ERROR"), anyString());
    }

    @Test
    @DisplayName("게이트웨이 비재시도 예외 시 복구 실패(NON_RETRYABLE_ERROR)로 기록한다.")
    void testRecoverRetryablePayment_NonRetryableFailure()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentEvent failedPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);
        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        when(mockPaymentProcessorUseCase.increaseRetryCount(any(PaymentEvent.class))).thenReturn(mockPaymentEvent);
        doThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR))
                .when(mockPaymentProcessorUseCase).confirmPaymentWithGateway(any());
        when(mockPaymentProcessorUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class))).thenReturn(
                failedPaymentEvent);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentRecoveryUseCase, times(1))
                .markRecoveryFailure(eq(mockPaymentEvent), eq("NON_RETRYABLE_ERROR"), anyString());
    }
}
