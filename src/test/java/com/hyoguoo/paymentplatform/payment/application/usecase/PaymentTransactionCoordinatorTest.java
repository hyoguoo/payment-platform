package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
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
    private OrderedProductUseCase orderedProductUseCase;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private PaymentOutboxUseCase paymentOutboxUseCase;

    @Mock
    private PaymentLoadUseCase paymentLoadUseCase;

    @Nested
    @DisplayName("Outbox 전략: executePayment + 재고 차감 + Outbox 생성 원자적 실행 테스트")
    class ExecutePaymentAndStockDecreaseWithOutboxTest {

        @Test
        @DisplayName("executePayment, 재고 차감, Outbox PENDING 생성을 모두 수행하고 IN_PROGRESS 이벤트를 반환한다")
        void executeSuccessfully() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentEvent readyEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox expectedOutbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.PENDING).retryCount(0).allArgsBuild();

            given(paymentCommandUseCase.executePayment(readyEvent, paymentKey)).willReturn(inProgressEvent);
            given(paymentOutboxUseCase.createPendingRecord(orderId)).willReturn(expectedOutbox);

            // when
            PaymentEvent result = coordinator.executePaymentAndStockDecreaseWithOutbox(
                    readyEvent, paymentKey, orderId, paymentOrderList);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);
            then(paymentCommandUseCase).should(times(1)).executePayment(readyEvent, paymentKey);
            then(orderedProductUseCase).should(times(1)).decreaseStockForOrders(paymentOrderList);
            then(paymentOutboxUseCase).should(times(1)).createPendingRecord(orderId);
        }

        @Test
        @DisplayName("재고 부족 시 예외가 발생하고 createPendingRecord()가 호출되지 않는다")
        void throwExceptionWhenStockNotEnough() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 100));
            PaymentEvent readyEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);

            given(paymentCommandUseCase.executePayment(readyEvent, paymentKey)).willReturn(inProgressEvent);
            org.mockito.BDDMockito.willThrow(PaymentOrderedProductStockException.of(
                    PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH))
                    .given(orderedProductUseCase).decreaseStockForOrders(anyList());

            // when & then
            assertThatThrownBy(() -> coordinator.executePaymentAndStockDecreaseWithOutbox(
                    readyEvent, paymentKey, orderId, paymentOrderList))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(paymentCommandUseCase).should(times(1)).executePayment(readyEvent, paymentKey);
            then(paymentOutboxUseCase).should(times(0)).createPendingRecord(anyString());
        }

        @Test
        @DisplayName("executePayment → 재고 차감 → createPendingRecord 순서로 실행된다")
        void executesInCorrectOrder() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            String paymentKey = "payment-key-123";
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentEvent readyEvent = createPaymentEvent(orderId, PaymentEventStatus.READY);
            PaymentEvent inProgressEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.PENDING).retryCount(0).allArgsBuild();

            given(paymentCommandUseCase.executePayment(readyEvent, paymentKey)).willReturn(inProgressEvent);
            given(paymentOutboxUseCase.createPendingRecord(orderId)).willReturn(outbox);

            // when
            coordinator.executePaymentAndStockDecreaseWithOutbox(
                    readyEvent, paymentKey, orderId, paymentOrderList);

            // then
            var inOrder = org.mockito.Mockito.inOrder(
                    paymentCommandUseCase, orderedProductUseCase, paymentOutboxUseCase);
            inOrder.verify(paymentCommandUseCase).executePayment(readyEvent, paymentKey);
            inOrder.verify(orderedProductUseCase).decreaseStockForOrders(paymentOrderList);
            inOrder.verify(paymentOutboxUseCase).createPendingRecord(orderId);
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
            then(orderedProductUseCase).should(times(1)).increaseStockForOrders(paymentOrderList);
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
            then(orderedProductUseCase).should(never()).increaseStockForOrders(anyList());
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
            then(orderedProductUseCase).should(never()).increaseStockForOrders(anyList());
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
            then(orderedProductUseCase).should(org.mockito.Mockito.never()).increaseStockForOrders(any());
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
            then(orderedProductUseCase).should(org.mockito.Mockito.never()).increaseStockForOrders(any());
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
