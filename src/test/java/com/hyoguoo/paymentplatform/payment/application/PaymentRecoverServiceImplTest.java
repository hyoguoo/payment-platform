package com.hyoguoo.paymentplatform.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
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
    private OrderedProductUseCase mockOrderedProductUseCase;
    private PaymentProcessorUseCase mockPaymentProcessorUseCase;
    private LocalDateTimeProvider mockLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockOrderedProductUseCase = Mockito.mock(OrderedProductUseCase.class);
        mockPaymentProcessorUseCase = Mockito.mock(PaymentProcessorUseCase.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);
        paymentRecoverService = new PaymentRecoverServiceImpl(
                mockPaymentLoadUseCase,
                mockOrderedProductUseCase,
                mockPaymentProcessorUseCase,
                mockLocalDateTimeProvider
        );
    }

    @Test
    @DisplayName("재시도 가능한 결제를 성공적으로 처리한다.")
    void testRecoverRetryablePayment_Success() throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);

        TossPaymentInfo mockTossPaymentInfo = Mockito.mock(TossPaymentInfo.class);
        TossPaymentDetails mockTossPaymentDetails = Mockito.mock(TossPaymentDetails.class);
        when(mockTossPaymentInfo.getPaymentDetails()).thenReturn(mockTossPaymentDetails);
        when(mockTossPaymentDetails.getApprovedAt()).thenReturn(LocalDateTime.now());

        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any())).thenReturn(mockTossPaymentInfo);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class), any(String.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).markPaymentAsDone(eq(mockPaymentEvent), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("재시도 불가능한 결제를 시도하면 결제 상태를 FAIL로 변경하고, 재고를 복구한다.")
    void testRecoverRetryablePayment_ValidNonRetryableFailure() {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentEvent failedPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);

        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(false);
        when(mockPaymentProcessorUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class))).thenReturn(failedPaymentEvent);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(0)).increaseRetryCount(any(PaymentEvent.class), any(String.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).markPaymentAsFail(any(PaymentEvent.class), any(String.class));
        verify(mockOrderedProductUseCase, times(1)).increaseStockForOrders(anyList());
    }

    @Test
    @DisplayName("재시도 가능한 결제 실패 중 다시 재시도 가능 예외가 발생하면 결제 상태를 UNKNOWN으로 변경하고, 재시도 횟수를 증가시킨다.")
    void testRecoverRetryablePayment_RetryableFailure()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);
        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        doThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR))
                .when(mockPaymentProcessorUseCase).confirmPaymentWithGateway(any());

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class), any(String.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class), any(String.class));
    }

    @Test
    @DisplayName("재시도 가능한 결제 실패 중 다시 재시도 불가능 예외가 발생하면 결제 상태를 Fail로 변경하고, 재고를 복구한다.")
    void testRecoverRetryablePayment_NonRetryableFailure()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        PaymentEvent mockPaymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentEvent failedPaymentEvent = Mockito.mock(PaymentEvent.class);
        List<PaymentEvent> paymentEvents = List.of(mockPaymentEvent);
        when(mockPaymentLoadUseCase.getRetryablePaymentEvents()).thenReturn(paymentEvents);
        when(mockLocalDateTimeProvider.now()).thenReturn(LocalDateTime.now());
        when(mockPaymentEvent.isRetryable(any(LocalDateTime.class))).thenReturn(true);
        doThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR))
                .when(mockPaymentProcessorUseCase).confirmPaymentWithGateway(any());
        when(mockPaymentProcessorUseCase.markPaymentAsFail(any(PaymentEvent.class), any(String.class))).thenReturn(failedPaymentEvent);

        // when
        paymentRecoverService.recoverRetryablePayment();

        // then
        verify(mockPaymentProcessorUseCase, times(1)).increaseRetryCount(any(PaymentEvent.class), any(String.class));
        verify(mockPaymentEvent, times(1)).isRetryable(any(LocalDateTime.class));
        verify(mockPaymentProcessorUseCase, times(1)).markPaymentAsFail(any(PaymentEvent.class), any(String.class));
        verify(mockOrderedProductUseCase, times(1)).increaseStockForOrders(anyList());
    }
}
