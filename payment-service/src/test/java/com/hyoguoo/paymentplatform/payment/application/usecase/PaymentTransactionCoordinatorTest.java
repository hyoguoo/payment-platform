package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    private PaymentLoadUseCase paymentLoadUseCase;

    @Mock
    private StockCachePort stockCachePort;

    @Mock
    private PaymentConfirmPublisherPort confirmPublisher;

    @Nested
    @DisplayName("decrementStock — 재고 캐시 원자 DECR 분기")
    class DecrementStockTest {

        @Test
        @DisplayName("모든 주문 품목에 대해 decrement=true 반환 시 SUCCESS")
        void returnsSuccessWhenAllItemsDecremented() {
            // given
            List<PaymentOrder> orderList = List.of(
                    createPaymentOrder(1L, 2),
                    createPaymentOrder(2L, 3)
            );
            given(stockCachePort.decrement(1L, 2)).willReturn(true);
            given(stockCachePort.decrement(2L, 3)).willReturn(true);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderList);

            // then
            assertThat(result).isEqualTo(StockDecrementResult.SUCCESS);
            then(stockCachePort).should(times(1)).decrement(1L, 2);
            then(stockCachePort).should(times(1)).decrement(2L, 3);
        }

        @Test
        @DisplayName("첫 번째 품목에서 decrement=false 반환 시 REJECTED, 이후 품목 호출 없음")
        void returnsRejectedAndShortCircuitsWhenDecrementFalse() {
            // given
            List<PaymentOrder> orderList = List.of(
                    createPaymentOrder(1L, 100),
                    createPaymentOrder(2L, 1)
            );
            given(stockCachePort.decrement(1L, 100)).willReturn(false);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderList);

            // then
            assertThat(result).isEqualTo(StockDecrementResult.REJECTED);
            then(stockCachePort).should(never()).decrement(2L, 1);
        }

        @Test
        @DisplayName("RuntimeException 발생 시 CACHE_DOWN 반환")
        void returnsCacheDownWhenRedisThrows() {
            // given
            List<PaymentOrder> orderList = List.of(createPaymentOrder(1L, 1));
            willThrow(new RuntimeException("Redis connection failure"))
                    .given(stockCachePort).decrement(1L, 1);

            // when
            StockDecrementResult result = coordinator.decrementStock(orderList);

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

    @Nested
    @DisplayName("Outbox 전략: 결제 성공 완료 처리 (WithOutbox) 테스트")
    class ExecutePaymentSuccessCompletionWithOutboxTest {

        @Test
        @DisplayName("성공 시 PaymentEvent DONE 및 outbox toDone 저장")
        void 성공_시_PaymentEvent_DONE_및_outbox_toDone_저장() {
            // given
            String orderId = "order-123";
            LocalDateTime approvedAt = LocalDateTime.now();
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent donePaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.DONE);

            given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                    .willReturn(donePaymentEvent);

            // when
            PaymentEvent result = coordinator.executePaymentSuccessCompletionWithOutbox(
                    paymentEvent, approvedAt, outbox);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.DONE);
            then(paymentOutboxUseCase).should(times(1)).save(outbox);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsDone(paymentEvent, approvedAt);
        }
    }

    @Nested
    @DisplayName("Outbox 전략: 결제 실패 보상 처리 (WithOutbox) 테스트")
    class ExecutePaymentFailureCompensationWithOutboxTest {

        @Test
        @DisplayName("outbox=IN_FLIGHT AND event=비종결(RETRYING) 재조회 시 재고 복원 + FAILED 전환")
        void executePaymentFailureCompensation_OutboxInFlight_EventNonTerminal_RestoresStock() {
            // given
            String orderId = "order-123";
            String failureReason = "결제 실패";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentOutbox freshOutbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent freshEvent = createPaymentEvent(orderId, PaymentEventStatus.RETRYING);
            PaymentEvent failedPaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.FAILED);

            given(paymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(freshOutbox));
            given(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(freshEvent);
            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(failedPaymentEvent);

            // when
            PaymentEvent result = coordinator.executePaymentFailureCompensationWithOutbox(
                    orderId, paymentOrderList, failureReason);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
            then(stockCachePort).should(times(1)).increment(1L, 2);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsFail(freshEvent, failureReason);
        }

        @Test
        @DisplayName("outbox=FAILED 재조회 시 재고 복원 건너뜀, markPaymentAsFail 호출")
        void executePaymentFailureCompensation_OutboxAlreadyFailed_SkipsStock() {
            // given
            String orderId = "order-123";
            String failureReason = "결제 실패";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentOutbox freshOutbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.FAILED).retryCount(0).allArgsBuild();
            PaymentEvent freshEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentEvent failedPaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.FAILED);

            given(paymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(freshOutbox));
            given(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(freshEvent);
            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(failedPaymentEvent);

            // when
            coordinator.executePaymentFailureCompensationWithOutbox(
                    orderId, paymentOrderList, failureReason);

            // then
            then(stockCachePort).should(never()).increment(anyLong(), anyInt());
            then(paymentCommandUseCase).should(times(1)).markPaymentAsFail(freshEvent, failureReason);
        }

        @ParameterizedTest
        @EnumSource(names = {"DONE", "FAILED", "QUARANTINED"})
        @DisplayName("outbox=IN_FLIGHT이지만 event=종결 상태 재조회 시 재고 복원 건너뜀")
        void executePaymentFailureCompensation_EventAlreadyTerminal_SkipsStock(PaymentEventStatus terminalStatus) {
            // given
            String orderId = "order-123";
            String failureReason = "결제 실패";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentOutbox freshOutbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent freshEvent = createPaymentEvent(orderId, terminalStatus);

            given(paymentOutboxUseCase.findByOrderId(orderId)).willReturn(Optional.of(freshOutbox));
            given(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).willReturn(freshEvent);
            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(freshEvent);

            // when
            coordinator.executePaymentFailureCompensationWithOutbox(
                    orderId, paymentOrderList, failureReason);

            // then
            then(stockCachePort).should(never()).increment(anyLong(), anyInt());
        }
    }

    @Nested
    @DisplayName("Outbox 전략: 재시도 처리 (executePaymentRetryWithOutbox) 테스트")
    class ExecutePaymentRetryWithOutboxTest {

        @Test
        @DisplayName("Outbox를 PENDING으로 복원하고 PaymentEvent를 RETRYING으로 전환한다")
        void executePaymentRetryWithOutbox_Outbox_PENDING_복원_및_PaymentEvent_RETRYING_전환() {
            // given
            String orderId = "order-123";
            LocalDateTime now = LocalDateTime.of(2026, 4, 7, 12, 0, 0);
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent retryingEvent = createPaymentEvent(orderId, PaymentEventStatus.RETRYING);

            given(paymentCommandUseCase.markPaymentAsRetrying(inProgressEvent)).willReturn(retryingEvent);

            // when
            PaymentEvent result = coordinator.executePaymentRetryWithOutbox(inProgressEvent, outbox, policy, now);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.RETRYING);
            then(paymentOutboxUseCase).should(times(1)).save(outbox);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsRetrying(inProgressEvent);
        }
    }

    @Nested
    @DisplayName("Outbox 전략: 격리 처리 (executePaymentQuarantineWithOutbox) 테스트")
    class ExecutePaymentQuarantineWithOutboxTest {

        @Test
        @DisplayName("outbox를 FAILED로, PaymentEvent를 QUARANTINED로 전환하고 재고 복구를 호출하지 않는다")
        void executePaymentQuarantineWithOutbox_MarksEventQuarantinedAndOutboxFailed() {
            // given
            String orderId = "order-123";
            String reason = "GATEWAY_STATUS_UNKNOWN";
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent quarantinedEvent = createPaymentEvent(orderId, PaymentEventStatus.QUARANTINED);

            given(paymentCommandUseCase.markPaymentAsQuarantined(inProgressEvent, reason))
                    .willReturn(quarantinedEvent);

            // when
            PaymentEvent result = coordinator.executePaymentQuarantineWithOutbox(inProgressEvent, outbox, reason);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
            then(paymentOutboxUseCase).should(times(1)).save(outbox);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(inProgressEvent, reason);
            then(stockCachePort).should(never()).increment(anyLong(), anyInt());
        }

        @Test
        @DisplayName("격리 처리 시 재고 복구(increaseStockForOrders)를 호출하지 않는다")
        void executePaymentQuarantineWithOutbox_DoesNotRestoreStock() {
            // given
            String orderId = "order-456";
            String reason = "GATEWAY_STATUS_UNKNOWN";
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(2L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent quarantinedEvent = createPaymentEvent(orderId, PaymentEventStatus.QUARANTINED);

            given(paymentCommandUseCase.markPaymentAsQuarantined(inProgressEvent, reason))
                    .willReturn(quarantinedEvent);

            // when
            coordinator.executePaymentQuarantineWithOutbox(inProgressEvent, outbox, reason);

            // then
            then(stockCachePort).should(never()).increment(anyLong(), anyInt());
        }

        @Test
        @DisplayName("executePaymentQuarantineWithOutbox 호출 시 QUARANTINED 전이 + quarantine_compensation_pending=true 플래그 set")
        void executePaymentQuarantine_SetsCompensationPendingFlag() {
            // given
            String orderId = "order-quarantine";
            String reason = "GATEWAY_STATUS_UNKNOWN";
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent quarantinedEvent = createPaymentEvent(orderId, PaymentEventStatus.QUARANTINED);

            given(paymentCommandUseCase.markPaymentAsQuarantined(inProgressEvent, reason))
                    .willReturn(quarantinedEvent);

            // when
            PaymentEvent result = coordinator.executePaymentQuarantineWithOutbox(inProgressEvent, outbox, reason);

            // then: QUARANTINED 홀딩 상태로 전환
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);
            then(paymentOutboxUseCase).should(times(1)).save(outbox);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsQuarantined(inProgressEvent, reason);
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
