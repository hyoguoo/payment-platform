package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChangeTrigger;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockHoldRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    private StockHoldRecordRepository stockHoldRecordRepository;

    @Mock
    private PaymentConfirmPublisherPort confirmPublisher;

    @Nested
    @DisplayName("decrementStock — 선점·상품 반복·부분 실패 되돌리기 (FakeStockCachePort 사용)")
    class DecrementStockAtomicTest {

        private FakeStockCachePort fakeStockCachePort;
        private FakeStockHoldRecordRepository fakeStockHoldRecordRepository;
        private PaymentTransactionCoordinator coordinatorWithFake;

        @BeforeEach
        void setUp() {
            fakeStockCachePort = new FakeStockCachePort();
            fakeStockHoldRecordRepository = new FakeStockHoldRecordRepository();
            coordinatorWithFake = new PaymentTransactionCoordinator(
                    paymentCommandUseCase,
                    paymentOutboxUseCase,
                    fakeStockCachePort,
                    fakeStockHoldRecordRepository,
                    confirmPublisher
            );
        }

        @Test
        @DisplayName("decrementStock_정상_차감_OK — 상품 전부 충분하면 전부 차감되고 SUCCESS 반환")
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
        @DisplayName("decrementStock_셋째_부족_앞의_둘만_되돌리고_REJECTED — 되돌린 상품만 재고가 원래 값으로 복원된다")
        void decrementStock_셋째_부족_앞의_둘만_되돌리고_REJECTED() {
            // given — 상품 3이 부족(재고 1 < 요청 5)
            String orderId = "order-005";
            fakeStockCachePort.set(1L, 10);
            fakeStockCachePort.set(2L, 10);
            fakeStockCachePort.set(3L, 1);
            List<PaymentOrder> orderList = List.of(
                    createPaymentOrder(1L, 2),
                    createPaymentOrder(2L, 3),
                    createPaymentOrder(3L, 5)
            );

            // when
            StockDecrementResult result = coordinatorWithFake.decrementStock(orderId, orderList);

            // then — 상품 1,2 는 원래 값으로 되돌아오고 상품 3(애초에 안 깎임)은 그대로다
            assertThat(result).isEqualTo(StockDecrementResult.REJECTED);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(10);
            assertThat(fakeStockCachePort.current(2L)).isEqualTo(10);
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(1);
        }

        @Test
        @DisplayName("decrementStock_이미_처리됨_상품은_되돌리기_대상에서_제외 — 남의(이전) 차감은 건드리지 않는다")
        void decrementStock_이미_처리됨_상품은_되돌리기_대상에서_제외() {
            // given — 상품 1은 이번 호출 이전에 이미 차감(ALREADY_DONE 유발), 상품 3은 부족
            String orderId = "order-006";
            fakeStockCachePort.set(1L, 10);
            fakeStockCachePort.set(2L, 10);
            fakeStockCachePort.set(3L, 10);
            coordinatorWithFake.decrementStock(orderId, List.of(createPaymentOrder(1L, 2)));
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(8);

            List<PaymentOrder> orderList = List.of(
                    createPaymentOrder(1L, 2),
                    createPaymentOrder(2L, 3),
                    createPaymentOrder(3L, 100)
            );

            // when
            StockDecrementResult result = coordinatorWithFake.decrementStock(orderId, orderList);

            // then — 상품 2(이번 호출이 직접 차감)만 되돌아오고, 상품 1(ALREADY_DONE)은 되돌리기 대상이
            // 아니라 이전 차감 상태(8) 그대로 유지된다. 되돌리기 대상이었다면 10으로 잘못 복원됐을 것이다
            assertThat(result).isEqualTo(StockDecrementResult.REJECTED);
            assertThat(fakeStockCachePort.current(1L)).isEqualTo(8);
            assertThat(fakeStockCachePort.current(2L)).isEqualTo(10);
            assertThat(fakeStockCachePort.current(3L)).isEqualTo(10);
        }

        @Test
        @DisplayName("decrementStock_반복이_끝나면_선점이_풀린다 — 해제 후 재요청이 새로 선점할 수 있다")
        void decrementStock_반복이_끝나면_선점이_풀린다() {
            // given
            String orderId = "order-007";
            fakeStockCachePort.set(1L, 10);
            List<PaymentOrder> orderList = List.of(createPaymentOrder(1L, 2));

            // when
            coordinatorWithFake.decrementStock(orderId, orderList);

            // then — 선점이 풀렸으므로 같은 orderId 로 재선점이 가능하다
            Optional<String> reacquired = fakeStockCachePort.acquireOrderLock(orderId);
            assertThat(reacquired).isPresent();
        }

        @Test
        @DisplayName("decrementStock_Redis_예외_CACHE_DOWN — RuntimeException → CACHE_DOWN 반환")
        void decrementStock_Redis_예외_CACHE_DOWN() {
            // given
            String orderId = "order-004";
            PaymentOrder order = createPaymentOrder(1L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, order);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order));

            // then
            assertThat(result).isEqualTo(StockDecrementResult.CACHE_DOWN);
            then(stockCachePort).should().releaseOrderLock(orderId, "lock-token");
        }

        @Test
        @DisplayName("decrementStock_선점_실패시_ALREADY_PROCESSING — 재고·기록을 건드리지 않고 물러난다")
        void decrementStock_선점_실패시_ALREADY_PROCESSING() {
            // given
            String orderId = "order-008";
            PaymentOrder order = createPaymentOrder(1L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.empty());

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order));

            // then
            assertThat(result).isEqualTo(StockDecrementResult.ALREADY_PROCESSING);
            then(stockCachePort).should(never()).decrementAtomic(anyString(), any(PaymentOrder.class));
            then(stockHoldRecordRepository).should(never()).openHold(anyString(), any(PaymentOrder.class));
            then(stockCachePort).should(never()).releaseOrderLock(anyString(), anyString());
        }

        @Test
        @DisplayName("decrementStock_캐시_예외_직전_직접_차감_성공분만_되돌리기_시도 — 셋째에서 예외가 나면 앞의 둘을 되돌리려 시도한다")
        void decrementStock_캐시_예외_직전_직접_차감_성공분만_되돌리기_시도() {
            // given
            String orderId = "order-010";
            PaymentOrder order1 = createPaymentOrder(1L, 1);
            PaymentOrder order2 = createPaymentOrder(2L, 1);
            PaymentOrder order3 = createPaymentOrder(3L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            given(stockCachePort.decrementAtomic(orderId, order1)).willReturn(StockDecrementAtomicResult.OK);
            given(stockCachePort.decrementAtomic(orderId, order2)).willReturn(StockDecrementAtomicResult.OK);
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, order3);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order1, order2, order3));

            // then — 직접 차감에 성공한 상품 1,2 만 되돌리기 대상이고 예외가 난 상품 3 은 대상이 아니다
            assertThat(result).isEqualTo(StockDecrementResult.CACHE_DOWN);
            then(stockCachePort).should().rejectCompensate(orderId, order1);
            then(stockCachePort).should().rejectCompensate(orderId, order2);
            then(stockCachePort).should(never()).rejectCompensate(orderId, order3);
            then(stockCachePort).should().releaseOrderLock(orderId, "lock-token");
        }

        @Test
        @DisplayName("decrementStock_되돌리기까지_실패해도_CACHE_DOWN — 되돌리기 예외를 삼키지 않고 로그만 남긴 뒤 격리로 넘긴다")
        void decrementStock_되돌리기까지_실패해도_CACHE_DOWN() {
            // given
            String orderId = "order-011";
            PaymentOrder order1 = createPaymentOrder(1L, 1);
            PaymentOrder order2 = createPaymentOrder(2L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            given(stockCachePort.decrementAtomic(orderId, order1)).willReturn(StockDecrementAtomicResult.OK);
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, order2);
            willThrow(new RuntimeException("되돌리기도 인프라 장애로 실패"))
                    .given(stockCachePort).rejectCompensate(orderId, order1);

            // when — 되돌리기 예외가 밖으로 전파되지 않고 CACHE_DOWN 으로 흡수된다
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order1, order2));

            // then
            assertThat(result).isEqualTo(StockDecrementResult.CACHE_DOWN);
            then(stockCachePort).should().releaseOrderLock(orderId, "lock-token");
        }

        @Test
        @DisplayName("decrementStock_직접_차감_성공분_없이_예외면_되돌리기_시도_안함 — 첫 상품에서 곧바로 캐시 예외면 되돌리기 호출이 없다")
        void decrementStock_직접_차감_성공분_없이_예외면_되돌리기_시도_안함() {
            // given
            String orderId = "order-012";
            PaymentOrder order1 = createPaymentOrder(1L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, order1);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order1));

            // then
            assertThat(result).isEqualTo(StockDecrementResult.CACHE_DOWN);
            then(stockCachePort).should(never()).rejectCompensate(anyString(), any(PaymentOrder.class));
        }

        @Test
        @DisplayName("decrementStock_캐시_예외에도_기록은_남는다 — 예외를 던진 상품까지 openHold 가 먼저 호출돼 회수 대상이 된다")
        void decrementStock_캐시_예외에도_기록은_남는다() {
            // given
            String orderId = "order-013";
            PaymentOrder order1 = createPaymentOrder(1L, 1);
            PaymentOrder order2 = createPaymentOrder(2L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            given(stockCachePort.decrementAtomic(orderId, order1)).willReturn(StockDecrementAtomicResult.OK);
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrementAtomic(orderId, order2);

            // when
            coordinator.decrementStock(orderId, List.of(order1, order2));

            // then — 차감에 실패한 상품도 openHold 가 이미 호출돼 기록이 남았다
            then(stockHoldRecordRepository).should().openHold(orderId, order1);
            then(stockHoldRecordRepository).should().openHold(orderId, order2);
        }

        @Test
        @DisplayName("decrementStock_기록이_차감보다_먼저_적힌다 — 상품마다 openHold 가 decrementAtomic 보다 먼저 호출된다")
        void decrementStock_기록이_차감보다_먼저_적힌다() {
            // given
            String orderId = "order-009";
            PaymentOrder order1 = createPaymentOrder(1L, 1);
            PaymentOrder order2 = createPaymentOrder(2L, 1);
            given(stockCachePort.acquireOrderLock(orderId)).willReturn(Optional.of("lock-token"));
            given(stockCachePort.decrementAtomic(orderId, order1)).willReturn(StockDecrementAtomicResult.OK);
            given(stockCachePort.decrementAtomic(orderId, order2)).willReturn(StockDecrementAtomicResult.OK);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderId, List.of(order1, order2));

            // then
            assertThat(result).isEqualTo(StockDecrementResult.SUCCESS);
            InOrder inOrder = org.mockito.Mockito.inOrder(stockHoldRecordRepository, stockCachePort);
            inOrder.verify(stockHoldRecordRepository).openHold(orderId, order1);
            inOrder.verify(stockCachePort).decrementAtomic(orderId, order1);
            inOrder.verify(stockHoldRecordRepository).openHold(orderId, order2);
            inOrder.verify(stockCachePort).decrementAtomic(orderId, order2);
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

            given(paymentCommandUseCase.markPaymentAsQuarantined(any(PaymentEvent.class), anyString(), anyString()))
                    .willReturn(quarantinedEvent);

            // when
            PaymentEvent result = coordinator.markStockCacheDownQuarantine(readyEvent);

            // then: QUARANTINED 홀딩 상태로 전환
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
            then(paymentCommandUseCase).should(times(1))
                    .markPaymentAsQuarantined(readyEvent, "재고 캐시 장애로 인한 격리",
                            PaymentStatusChangeTrigger.STOCK_CACHE_DOWN);
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
            InOrder inOrder = org.mockito.Mockito.inOrder(paymentCommandUseCase, paymentOutboxUseCase, confirmPublisher);
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
