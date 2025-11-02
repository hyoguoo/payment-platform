package com.hyoguoo.paymentplatform.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandrUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentExpirationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentExpirationServiceImplTest {

    private PaymentExpirationService paymentExpirationService;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandrUseCase mockPaymentCommandrUseCase;

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandrUseCase = Mockito.mock(PaymentCommandrUseCase.class);
        paymentExpirationService = new PaymentExpirationServiceImpl(
                mockPaymentLoadUseCase,
                mockPaymentCommandrUseCase
        );
    }

    @Test
    @DisplayName("30분이 지난 READY 상태의 결제를 성공적으로 만료 처리한다.")
    void testExpireOldReadyPayments_Success() {
        // given
        LocalDateTime thirtyOneMinutesAgo = LocalDateTime.now().minusMinutes(31);

        List<PaymentOrder> paymentOrderList = new ArrayList<>();
        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId("order123")
                .productId(1L)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(10000))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();
        paymentOrderList.add(paymentOrder);

        PaymentEvent mockReadyPayment = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("Test Order")
                .orderId("order123")
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(paymentOrderList)
                .createdAt(thirtyOneMinutesAgo)
                .allArgsBuild();

        PaymentEvent mockExpiredPayment = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("Test Order")
                .orderId("order123")
                .status(PaymentEventStatus.EXPIRED)
                .retryCount(0)
                .paymentOrderList(paymentOrderList)
                .createdAt(thirtyOneMinutesAgo)
                .allArgsBuild();

        List<PaymentEvent> readyPayments = List.of(mockReadyPayment);

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(readyPayments);
        when(mockPaymentCommandrUseCase.expirePayment(mockReadyPayment)).thenReturn(mockExpiredPayment);

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandrUseCase, times(1)).expirePayment(mockReadyPayment);
    }

    @Test
    @DisplayName("만료 대상이 없을 경우 아무 처리도 하지 않는다.")
    void testExpireOldReadyPayments_NoExpiredPayments() {
        // given
        List<PaymentEvent> emptyList = List.of();
        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(emptyList);

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandrUseCase, times(0)).expirePayment(any(PaymentEvent.class));
    }

    @Test
    @DisplayName("여러 개의 READY 상태 결제를 한 번에 만료 처리한다.")
    void testExpireOldReadyPayments_MultiplePayments() {
        // given
        List<PaymentEvent> readyPayments = new ArrayList<>();
        List<PaymentEvent> expiredPayments = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            List<PaymentOrder> orderList = new ArrayList<>();
            PaymentOrder order = PaymentOrder.allArgsBuilder()
                    .id((long) i)
                    .paymentEventId((long) i)
                    .orderId("order" + i)
                    .productId((long) i)
                    .quantity(i)
                    .totalAmount(BigDecimal.valueOf(10000 * i))
                    .status(PaymentOrderStatus.NOT_STARTED)
                    .allArgsBuild();
            orderList.add(order);

            PaymentEvent readyPayment = PaymentEvent.allArgsBuilder()
                    .id((long) i)
                    .buyerId((long) i)
                    .sellerId((long) i)
                    .orderName("Order " + i)
                    .orderId("order" + i)
                    .status(PaymentEventStatus.READY)
                    .retryCount(0)
                    .paymentOrderList(orderList)
                    .createdAt(LocalDateTime.now().minusMinutes(31))
                    .allArgsBuild();

            readyPayments.add(readyPayment);

            List<PaymentOrder> expiredOrderList = new ArrayList<>();
            PaymentOrder expiredOrder = PaymentOrder.allArgsBuilder()
                    .id((long) i)
                    .paymentEventId((long) i)
                    .orderId("order" + i)
                    .productId((long) i)
                    .quantity(i)
                    .totalAmount(BigDecimal.valueOf(10000 * i))
                    .status(PaymentOrderStatus.EXPIRED)
                    .allArgsBuild();
            expiredOrderList.add(expiredOrder);

            PaymentEvent expiredPayment = PaymentEvent.allArgsBuilder()
                    .id((long) i)
                    .buyerId((long) i)
                    .sellerId((long) i)
                    .orderName("Order " + i)
                    .orderId("order" + i)
                    .status(PaymentEventStatus.EXPIRED)
                    .retryCount(0)
                    .paymentOrderList(expiredOrderList)
                    .createdAt(LocalDateTime.now().minusMinutes(31))
                    .allArgsBuild();

            expiredPayments.add(expiredPayment);
        }

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(readyPayments);

        for (int i = 0; i < readyPayments.size(); i++) {
            when(mockPaymentCommandrUseCase.expirePayment(readyPayments.get(i)))
                    .thenReturn(expiredPayments.get(i));
        }

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandrUseCase, times(3)).expirePayment(any(PaymentEvent.class));
        for (PaymentEvent payment : readyPayments) {
            verify(mockPaymentCommandrUseCase, times(1)).expirePayment(payment);
        }
    }
}
