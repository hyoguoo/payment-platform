package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentExpirationSkipMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.application.port.in.PaymentExpirationService;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentExpirationServiceImplTest {

    private PaymentExpirationService paymentExpirationService;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentExpirationSkipMetrics mockPaymentExpirationSkipMetrics;

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockPaymentExpirationSkipMetrics = Mockito.mock(PaymentExpirationSkipMetrics.class);
        paymentExpirationService = new PaymentExpirationServiceImpl(
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockPaymentExpirationSkipMetrics
        );
    }

    @Test
    @DisplayName("30분이 지난 READY 상태의 결제를 성공적으로 만료 처리한다.")
    void testExpireOldReadyPayments_Success() {
        // given
        Instant thirtyOneMinutesAgo = Instant.now().minus(31, ChronoUnit.MINUTES);

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
                .paymentOrderList(paymentOrderList)
                .createdAt(thirtyOneMinutesAgo)
                .allArgsBuild();

        List<PaymentEvent> readyPayments = List.of(mockReadyPayment);

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(readyPayments);
        when(mockPaymentCommandUseCase.expirePayment(mockReadyPayment)).thenReturn(mockExpiredPayment);

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandUseCase, times(1)).expirePayment(mockReadyPayment);
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
        verify(mockPaymentCommandUseCase, times(0)).expirePayment(any(PaymentEvent.class));
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
                    .paymentOrderList(orderList)
                    .createdAt(Instant.now().minus(31, ChronoUnit.MINUTES))
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
                    .paymentOrderList(expiredOrderList)
                    .createdAt(Instant.now().minus(31, ChronoUnit.MINUTES))
                    .allArgsBuild();

            expiredPayments.add(expiredPayment);
        }

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(readyPayments);

        for (int i = 0; i < readyPayments.size(); i++) {
            when(mockPaymentCommandUseCase.expirePayment(readyPayments.get(i)))
                    .thenReturn(expiredPayments.get(i));
        }

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandUseCase, times(3)).expirePayment(any(PaymentEvent.class));
        for (PaymentEvent payment : readyPayments) {
            verify(mockPaymentCommandUseCase, times(1)).expirePayment(payment);
        }
    }

    // ---- 만료 2단 연쇄 명문화 — 만료 정책 회귀 가드 ----

    @Test
    @DisplayName("expireOldReadyPayments — READY 복원 이후 만료 대상인 결제를 성공적으로 만료 처리한다. (2단 연쇄 2단계)")
    void expireOldReadyPayments_afterReset_shouldSucceed() {
        // given — Reconciler 가 READY 로 복원한 결제(createdAt = Instant 기반)를 만료 서비스가 처리
        Instant thirtyOneMinutesAgo = Instant.now().minus(31, ChronoUnit.MINUTES);

        List<PaymentOrder> paymentOrderList = new ArrayList<>();
        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId("order-reset-001")
                .productId(1L)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(10000))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();
        paymentOrderList.add(paymentOrder);

        // Reconciler 가 resetToReady() 완료 후 READY 상태가 된 결제 — lastStatusChangedAt = Instant 기반
        PaymentEvent restoredReadyPayment = PaymentEvent.allArgsBuilder()
                .id(10L)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("Reconciler 복원 주문")
                .orderId("order-reset-001")
                .status(PaymentEventStatus.READY)
                .paymentOrderList(paymentOrderList)
                .lastStatusChangedAt(thirtyOneMinutesAgo)
                .allArgsBuild();

        PaymentEvent expiredPayment = PaymentEvent.allArgsBuilder()
                .id(10L)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("Reconciler 복원 주문")
                .orderId("order-reset-001")
                .status(PaymentEventStatus.EXPIRED)
                .paymentOrderList(paymentOrderList)
                .lastStatusChangedAt(Instant.now())
                .allArgsBuild();

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder()).thenReturn(List.of(restoredReadyPayment));
        when(mockPaymentCommandUseCase.expirePayment(restoredReadyPayment)).thenReturn(expiredPayment);

        // when
        paymentExpirationService.expireOldReadyPayments();

        // then — 2단 연쇄의 2단계: READY 복원 이후 만료 서비스가 EXPIRED 처리
        verify(mockPaymentLoadUseCase, times(1)).getReadyPaymentsOlder();
        verify(mockPaymentCommandUseCase, times(1)).expirePayment(restoredReadyPayment);
    }

    // ---- poison-pill 격리 (L-14) — stranded 1건이 정상 건 만료를 막지 않는다 ----

    @Test
    @DisplayName("expireOldReadyPayments — 한 건이 만료 불가(stranded)로 예외를 던져도 나머지 정상 건은 모두 만료한다 (poison-pill 격리)")
    void expireOldReadyPayments_oneStranded_doesNotBlockOthers() {
        // given — order EXECUTING 잔류 등으로 expire 시 예외를 던지는 stranded 1건 + 정상 2건
        PaymentEvent stranded = readyPayment(1L, "order-stranded");
        PaymentEvent normal1 = readyPayment(2L, "order-normal-1");
        PaymentEvent normal2 = readyPayment(3L, "order-normal-2");

        when(mockPaymentLoadUseCase.getReadyPaymentsOlder())
                .thenReturn(List.of(stranded, normal1, normal2));
        when(mockPaymentCommandUseCase.expirePayment(stranded))
                .thenThrow(PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXPIRE));
        when(mockPaymentCommandUseCase.expirePayment(normal1)).thenReturn(normal1);
        when(mockPaymentCommandUseCase.expirePayment(normal2)).thenReturn(normal2);

        // when — stranded 예외가 배치를 중단/롤백시키지 않아야 한다
        assertThatCode(() -> paymentExpirationService.expireOldReadyPayments())
                .doesNotThrowAnyException();

        // then — 정상 2건 모두 만료 시도됨 (한 건의 실패가 격리됨) + 격리된 실패는 metric 으로 가시화
        verify(mockPaymentCommandUseCase, times(1)).expirePayment(normal1);
        verify(mockPaymentCommandUseCase, times(1)).expirePayment(normal2);
        verify(mockPaymentExpirationSkipMetrics, times(1)).recordSkip(any(String.class), any());
    }

    private PaymentEvent readyPayment(long id, String orderId) {
        List<PaymentOrder> orderList = new ArrayList<>();
        orderList.add(PaymentOrder.allArgsBuilder()
                .id(id)
                .paymentEventId(id)
                .orderId(orderId)
                .productId(id)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(10000))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild());

        return PaymentEvent.allArgsBuilder()
                .id(id)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("Order " + orderId)
                .orderId(orderId)
                .status(PaymentEventStatus.READY)
                .paymentOrderList(orderList)
                .createdAt(Instant.now().minus(31, ChronoUnit.MINUTES))
                .allArgsBuild();
    }
}
