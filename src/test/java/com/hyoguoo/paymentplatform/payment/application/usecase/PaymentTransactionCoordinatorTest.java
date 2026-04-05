package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
    private OrderedProductUseCase orderedProductUseCase;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private PaymentOutboxUseCase paymentOutboxUseCase;

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
        @DisplayName("성공 시 재고복원, PaymentEvent FAILED, outbox toFailed 저장")
        void 성공_시_재고복원_PaymentEvent_FAILED_outbox_toFailed_저장() {
            // given
            String orderId = "order-123";
            String failureReason = "결제 실패";
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            List<PaymentOrder> paymentOrderList = List.of(createPaymentOrder(1L, 2));
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .id(1L).orderId(orderId).status(PaymentOutboxStatus.IN_FLIGHT).retryCount(0).allArgsBuild();
            PaymentEvent failedPaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.FAILED);

            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(failedPaymentEvent);

            // when
            PaymentEvent result = coordinator.executePaymentFailureCompensationWithOutbox(
                    paymentEvent, paymentOrderList, failureReason, outbox);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.FAILED);
            then(paymentOutboxUseCase).should(times(1)).save(outbox);
            then(orderedProductUseCase).should(times(1)).increaseStockForOrders(paymentOrderList);
            then(paymentCommandUseCase).should(times(1)).markPaymentAsFail(paymentEvent, failureReason);
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
