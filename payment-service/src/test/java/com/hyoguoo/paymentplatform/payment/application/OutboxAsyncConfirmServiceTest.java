package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("OutboxAsyncConfirmService 테스트")
class OutboxAsyncConfirmServiceTest {

    private OutboxAsyncConfirmService outboxAsyncConfirmService;

    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentFailureUseCase mockPaymentFailureUseCase;
    private PaymentConfirmPublisherPort mockConfirmPublisher;
    private PaymentConfirmChannel mockChannel;

    @BeforeEach
    void setUp() {
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentFailureUseCase = Mockito.mock(PaymentFailureUseCase.class);
        mockConfirmPublisher = Mockito.mock(PaymentConfirmPublisherPort.class);
        mockChannel = Mockito.mock(PaymentConfirmChannel.class);

        outboxAsyncConfirmService = new OutboxAsyncConfirmService(
                mockTransactionCoordinator,
                mockPaymentLoadUseCase,
                mockPaymentFailureUseCase,
                mockConfirmPublisher,
                mockChannel
        );
    }

    @Nested
    @DisplayName("confirm() — 재고 차감 SUCCESS 경로")
    class ConfirmSuccessTest {

        @Test
        @DisplayName("decrementStock=SUCCESS → executeConfirmTx 1회 호출")
        void callsExecuteConfirmTxOnSuccess() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, paymentKey, amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);
            given(mockTransactionCoordinator.executeConfirmTx(
                    any(PaymentEvent.class), anyString(), anyString()))
                    .willReturn(inProgressEvent);

            // when
            outboxAsyncConfirmService.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1))
                    .decrementStock(paymentEvent.getPaymentOrderList());
            then(mockTransactionCoordinator).should(times(1))
                    .executeConfirmTx(paymentEvent, paymentKey, orderId);
        }

        @Test
        @DisplayName("SUCCESS 시 orderId와 amount를 반환한다")
        void returnsOrderIdAndAmount() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);

            // when
            PaymentConfirmAsyncResult result = outboxAsyncConfirmService.confirm(command);

            // then
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getAmount()).isEqualByComparingTo(amount);
        }

        @Test
        @DisplayName("SUCCESS 시 confirmPublisher.publish(orderId)를 1회 호출한다")
        void publishesOnceOnSuccess() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);

            // when
            outboxAsyncConfirmService.confirm(command);

            // then
            then(mockConfirmPublisher).should(times(1)).publish(eq(orderId), any(), any(), anyString());
        }

        @Test
        @DisplayName("채널 여유 있을 때 queueNearFull=false")
        void queueNearFullFalse() throws PaymentOrderedProductStockException {
            // given
            given(mockChannel.isNearFull()).willReturn(false);
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);

            // when
            PaymentConfirmAsyncResult result = outboxAsyncConfirmService.confirm(command);

            // then
            assertThat(result.isQueueNearFull()).isFalse();
        }

        @Test
        @DisplayName("채널 거의 찼을 때 queueNearFull=true")
        void queueNearFullTrue() throws PaymentOrderedProductStockException {
            // given
            given(mockChannel.isNearFull()).willReturn(true);
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);

            // when
            PaymentConfirmAsyncResult result = outboxAsyncConfirmService.confirm(command);

            // then
            assertThat(result.isQueueNearFull()).isTrue();
        }
    }

    @Nested
    @DisplayName("confirm() — 재고 부족 REJECTED 경로")
    class ConfirmRejectedTest {

        @Test
        @DisplayName("decrementStock=REJECTED → handleStockFailure 호출 + PaymentOrderedProductStockException throw")
        void rejectedCallsHandleStockFailureAndThrows() {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(10000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.REJECTED);

            // when & then
            assertThatThrownBy(() -> outboxAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(mockPaymentFailureUseCase).should(times(1))
                    .handleStockFailure(eq(paymentEvent), anyString());
            then(mockTransactionCoordinator).should(never())
                    .executeConfirmTx(any(), anyString(), anyString());
            then(mockConfirmPublisher).should(never()).publish(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("confirm() — 캐시 장애 CACHE_DOWN 경로")
    class ConfirmCacheDownTest {

        @Test
        @DisplayName("decrementStock=CACHE_DOWN → markStockCacheDownQuarantine 호출 + PaymentOrderedProductStockException throw")
        void cacheDownQuarantinesAndThrows() {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(10000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.CACHE_DOWN);

            // when & then
            assertThatThrownBy(() -> outboxAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(mockTransactionCoordinator).should(times(1))
                    .markStockCacheDownQuarantine(paymentEvent);
            then(mockTransactionCoordinator).should(never())
                    .executeConfirmTx(any(), anyString(), anyString());
            then(mockConfirmPublisher).should(never()).publish(any(), any(), any(), any());
            then(mockPaymentFailureUseCase).should(never())
                    .handleStockFailure(any(), anyString());
        }
    }

    @Nested
    @DisplayName("LVAL 로컬 검증 — 검증 실패 시 재고 차감 호출 없음")
    class LvalValidationTest {

        @Test
        @DisplayName("userId 불일치 시 PaymentValidException, decrementStock 호출 없음")
        void userIdMismatchThrowsAndSkipsStockDecrement() {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(999L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);

            // when & then
            assertThatThrownBy(() -> outboxAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentValidException.class);

            then(mockTransactionCoordinator).should(never()).decrementStock(anyList());
        }

        @Test
        @DisplayName("금액 불일치 시 PaymentValidException, decrementStock 호출 없음")
        void amountMismatchThrowsAndSkipsStockDecrement() {
            // given
            String orderId = "order-123";
            BigDecimal eventAmount = BigDecimal.valueOf(15000);
            BigDecimal commandAmount = BigDecimal.valueOf(9999);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", commandAmount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, eventAmount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);

            // when & then
            assertThatThrownBy(() -> outboxAsyncConfirmService.confirm(command))
                    .isInstanceOf(PaymentValidException.class);

            then(mockTransactionCoordinator).should(never()).decrementStock(anyList());
        }

        @Test
        @DisplayName("금액 일치 시 정상 플로우 — decrementStock + executeConfirmTx 호출")
        void amountMatchProceedsNormally() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            BigDecimal amount = BigDecimal.valueOf(15000);
            PaymentConfirmCommand command = buildCommand(1L, orderId, "payment-key", amount);
            PaymentEvent paymentEvent = createPaymentEventWithAmount(orderId, PaymentEventStatus.READY, amount);

            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(paymentEvent);
            given(mockTransactionCoordinator.decrementStock(anyList()))
                    .willReturn(StockDecrementResult.SUCCESS);

            // when
            outboxAsyncConfirmService.confirm(command);

            // then
            then(mockTransactionCoordinator).should(times(1)).decrementStock(anyList());
            then(mockTransactionCoordinator).should(times(1))
                    .executeConfirmTx(any(), anyString(), anyString());
        }
    }

    private PaymentConfirmCommand buildCommand(Long userId, String orderId, String paymentKey, BigDecimal amount) {
        return PaymentConfirmCommand.builder()
                .userId(userId)
                .orderId(orderId)
                .paymentKey(paymentKey)
                .amount(amount)
                .build();
    }

    private PaymentEvent createPaymentEvent(String orderId, PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEventWithAmount(String orderId, PaymentEventStatus status, BigDecimal amount) {
        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .productId(1L)
                .quantity(1)
                .totalAmount(amount)
                .allArgsBuild();
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .status(status)
                .paymentOrderList(List.of(paymentOrder))
                .allArgsBuild();
    }
}
