package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("PaymentTransactionCoordinator 테스트")
@ExtendWith(MockitoExtension.class)
class PaymentTransactionCoordinatorTest {

    @InjectMocks
    private PaymentTransactionCoordinator coordinator;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private PaymentOutboxUseCase paymentOutboxUseCase;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private PaymentConfirmPublisherPort confirmPublisher;

    @Nested
    @DisplayName("decrementStock — atomic 1회 호출 분기 (FakeStockCachePort 사용)")
    class DecrementStockAtomicTest {

        private FakeStockCachePort fakeStockCachePort;
        private PaymentTransactionCoordinator coordinatorWithFake;

        @BeforeEach
        void setUp() {
            fakeStockCachePort = new FakeStockCachePort();
            coordinatorWithFake = new PaymentTransactionCoordinator(
                    paymentCommandUseCase,
                    paymentOutboxUseCase,
                    fakeStockCachePort,
                    confirmPublisher
            );
        }

        @Test
        @DisplayName("decrementStock_정상_차감_OK — decrementAtomic OK → SUCCESS 반환 + 재고 감소")
        void decrementStock_정상_차감_OK() {
            // given
            String orderId = "order-001";
            fakeStockCachePort.set(1L, 10);
            fakeStockCachePort.set(2L, 5);
            List<PaymentOrder> orderList = List.of(
                    createPaymentOrder(1L, 2),
                    createPaymentOrder(2L, 3)
            );

            // when
            StockDecrementResult result = coordinatorWithFake.decrementStock(orderId, orderList);

            // then
            assertThat(result).isEqualTo(StockDecrementResult.SUCCESS);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(8);
            assertThat(fakeStockCachePort.current(2L)).isEqualTo(2);
        }

        @Test
        @DisplayName("decrementStock_재고_부족_REJECTED — decrementAtomic INSUFFICIENT → REJECTED 반환 + 재고 불변")
        void decrementStock_재고_부족_REJECTED() {
            // given
            String orderId = "order-002";
            fakeStockCachePort.set(1L, 1);
            List<PaymentOrder> orderList = List.of(createPaymentOrder(1L, 100));

            // when
            StockDecrementResult result = coordinatorWithFake.decrementStock(orderId, orderList);

            // then
            assertThat(result).isEqualTo(StockDecrementResult.REJECTED);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(1);
        }

        @Test
        @DisplayName("decrementStock_ALREADY_DONE_은_SUCCESS — 동일 orderId 재호출 시 SUCCESS + 재고 변화 없음")
        void decrementStock_ALREADY_DONE_은_SUCCESS() {
            // given
            String orderId = "order-003";
            fakeStockCachePort.set(1L, 10);
            List<PaymentOrder> orderList = List.of(createPaymentOrder(1L, 2));

            coordinatorWithFake.decrementStock(orderId, orderList);
            int stockAfterFirst = fakeStockCachePort.current(1L);

            // when — 동일 orderId 재호출
            StockDecrementResult result = coordinatorWithFake.decrementStock(orderId, orderList);

            // then — ALREADY_DONE 은 SUCCESS 로 매핑, 재고 변화 없음
            assertThat(result).isEqualTo(StockDecrementResult.SUCCESS);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(stockAfterFirst);
        }

        @Test
        @DisplayName("decrementStock_Redis_예외_CACHE_DOWN — RuntimeException → CACHE_DOWN 반환")
        void decrementStock_Redis_예외_CACHE_DOWN() {
            // given
            String orderId = "order-004";
            List<PaymentOrder> orderList = List.of(createPaymentOrder(1L, 1));
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, orderList);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, orderList);

            // then
            assertThat(result).isEqualTo(StockDecrementResult.CACHE_DOWN);
        }
    }

    @Nested
    @DisplayName("markStockCacheDownQuarantine — cache 장애 분기")
    class MarkStockCacheDownQuarantineTest {

        @Test
        @DisplayName("QUARANTINED 전이 — 홀딩 상태로 전환됨")
        void marksQuarantined() {
            // given
            String orderId = "order-cd";
            PaymentEvent readyEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent quarantinedEvent = createPaymentEvent(orderId, PaymentEventStatus.QUARANTINED);

            given(paymentCommandUseCase.markPaymentAsQuarantined(any(PaymentEvent.class), anyString()))
                    .willReturn(quarantinedEvent);

            // when
            PaymentEvent result = coordinator.markStockCacheDownQuarantine(readyEvent);

            // then: QUARANTINED 홀딩 상태로 전환
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
            then(paymentCommandUseCase).should(times(1))
                    .markPaymentAsQuarantined(readyEvent, "재고 캐시 장애로 인한 격리");
        }
    }

    @Nested
    @DisplayName("executeConfirmTx — event 전이 + outbox PENDING 원자 커밋")
    class ExecuteConfirmTxTest {

        @Test
        @DisplayName("executePayment → createPendingRecord 순서로 실행되고 IN_PROGRESS 이벤트 반환")
        void executesExecutePaymentThenCreatePendingRecord() {
            // given
            String orderId = "order-tx";
            String paymentKey = "key-tx";
            PaymentEvent readyEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.PENDING).retryCount(0).allArgsBuild();

            given(paymentCommandUseCase.executePayment(readyEvent, paymentKey)).willReturn(inProgressEvent);
            given(paymentOutboxUseCase.createPendingRecord(orderId)).willReturn(outbox);

            // when
            PaymentEvent result = coordinator.executeConfirmTx(readyEvent, paymentKey, orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
            var inOrder = org.mockito.Mockito.inOrder(paymentCommandUseCase, paymentOutboxUseCase, confirmPublisher);
            inOrder.verify(paymentCommandUseCase).executePayment(readyEvent, paymentKey);
            inOrder.verify(paymentOutboxUseCase).createPendingRecord(orderId);
            inOrder.verify(confirmPublisher).publish(
                    org.mockito.ArgumentMatchers.eq(orderId),
                    any(),
                    any(),
                    org.mockito.ArgumentMatchers.eq(paymentKey)
            );
        }
    }

    private PaymentOrder createPaymentOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(10000))
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEvent(String orderId, PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(status)
                .paymentOrderList(java.util.Collections.emptyList())
                .allArgsBuild();
    }
}
