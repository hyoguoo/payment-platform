package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossConfirmException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentConfirmServiceImplTest {

    private PaymentConfirmServiceImpl paymentConfirmService;
    private OrderedProductUseCase mockOrderedProductUseCase;
    private PaymentProcessorUseCase mockPaymentProcessorUseCase;

    private static MockConfirmData getDefaultMockConfirmData() {
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .userId(1L)
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal("10000"))
                .build();

        PaymentOrder mockPaymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId("order123")
                .productId(1L)
                .quantity(1)
                .totalAmount(new BigDecimal("10000"))
                .allArgsBuild();

        PaymentEvent mockPaymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(2L)
                .orderId("order123")
                .paymentKey("paymentKey")
                .status(PaymentEventStatus.IN_PROGRESS)
                .approvedAt(null)
                .paymentOrderList(List.of(mockPaymentOrder))
                .allArgsBuild();

        return new MockConfirmData(paymentConfirmCommand, mockPaymentEvent);
    }

    @BeforeEach
    void setUp() {
        mockOrderedProductUseCase = Mockito.mock(OrderedProductUseCase.class);
        mockPaymentProcessorUseCase = Mockito.mock(PaymentProcessorUseCase.class);
        paymentConfirmService = new PaymentConfirmServiceImpl(
                mockOrderedProductUseCase, mockPaymentProcessorUseCase
        );

        Mockito.clearInvocations(mockOrderedProductUseCase, mockPaymentProcessorUseCase);
    }

    @Test
    @DisplayName("성공적으로 결제를 확인하고 결제 완료 처리한다.")
    void testConfirm_Success()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException, PaymentOrderedProductStockException {
        // given
        MockConfirmData mockConfirmData = getDefaultMockConfirmData();

        TossPaymentInfo mockTossPaymentInfo = TossPaymentInfo.builder()
                .paymentDetails(
                        TossPaymentDetails.builder()
                                .approvedAt(LocalDateTime.now())
                                .build()
                )
                .build();

        // when
        when(mockPaymentProcessorUseCase.findAndExecutePayment(any(PaymentConfirmCommand.class)))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .thenReturn(mockTossPaymentInfo);
        when(mockPaymentProcessorUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                .thenReturn(mockConfirmData.mockPaymentEvent());

        PaymentConfirmResult result = paymentConfirmService.confirm(mockConfirmData.paymentConfirmCommand());

        // then
        assertThat(result.getOrderId()).isEqualTo(mockConfirmData.mockPaymentEvent().getOrderId());
        assertThat(result.getAmount()).isEqualTo(mockConfirmData.mockPaymentEvent().getTotalAmount());
        verify(mockOrderedProductUseCase, times(1))
                .decreaseStockForOrders(mockConfirmData.mockPaymentEvent().getPaymentOrderList());
    }

    @Test
    @DisplayName("재시도 가능한 결제 오류 발생 시 예외를 던지고 결제 상태를 '알 수 없음'으로 설정한다.")
    void testConfirm_RetryableFailure() throws Exception {
        // given
        MockConfirmData mockConfirmData = getDefaultMockConfirmData();

        // when
        when(mockPaymentProcessorUseCase.findAndExecutePayment(any(PaymentConfirmCommand.class)))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .thenThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR));

        // then
        PaymentConfirmCommand mockPaymentConfirmCommand = mockConfirmData.paymentConfirmCommand();
        assertThatThrownBy(() -> paymentConfirmService.confirm(mockPaymentConfirmCommand))
                .isInstanceOf(PaymentTossConfirmException.class);

        verify(mockPaymentProcessorUseCase, times(1))
                .markPaymentAsUnknown(mockConfirmData.mockPaymentEvent());
    }

    @Test
    @DisplayName("재시도 불가능한 결제 오류 발생 시 예외를 던지고 재고를 복구한다.")
    void testConfirm_NonRetryableFailure() throws Exception {
        // given
        MockConfirmData mockConfirmData = getDefaultMockConfirmData();

        // when
        when(mockPaymentProcessorUseCase.findAndExecutePayment(any(PaymentConfirmCommand.class)))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.markPaymentAsFail(mockConfirmData.mockPaymentEvent()))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .thenThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR));

        // then
        PaymentConfirmCommand mockPaymentConfirmCommand = mockConfirmData.paymentConfirmCommand();
        assertThatThrownBy(() -> paymentConfirmService.confirm(mockPaymentConfirmCommand))
                .isInstanceOf(PaymentTossConfirmException.class);

        verify(mockPaymentProcessorUseCase, times(1))
                .markPaymentAsFail(mockConfirmData.mockPaymentEvent());
        verify(mockOrderedProductUseCase, times(1))
                .increaseStockForOrders(mockConfirmData.mockPaymentEvent().getPaymentOrderList());
    }

    @Test
    @DisplayName("런타임 오류 발생 시 예외를 던지고 재고 복구 로직을 호출한다.")
    void testConfirm_RuntimeException()
            throws PaymentTossNonRetryableException, PaymentTossRetryableException {
        // given
        MockConfirmData mockConfirmData = getDefaultMockConfirmData();

        // when
        when(mockPaymentProcessorUseCase.findAndExecutePayment(any(PaymentConfirmCommand.class)))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.markPaymentAsFail(mockConfirmData.mockPaymentEvent()))
                .thenReturn(mockConfirmData.mockPaymentEvent());
        when(mockPaymentProcessorUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

        // then
        PaymentConfirmCommand mockPaymentConfirmCommand = mockConfirmData.paymentConfirmCommand();
        assertThatThrownBy(() -> paymentConfirmService.confirm(mockPaymentConfirmCommand))
                .isInstanceOf(RuntimeException.class);

        verify(mockPaymentProcessorUseCase, times(1))
                .markPaymentAsFail(mockConfirmData.mockPaymentEvent());
        verify(mockOrderedProductUseCase, times(1))
                .increaseStockForOrders(mockConfirmData.mockPaymentEvent().getPaymentOrderList());
    }

    private record MockConfirmData(PaymentConfirmCommand paymentConfirmCommand, PaymentEvent mockPaymentEvent) {

    }
}
