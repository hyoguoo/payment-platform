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
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
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
    private PaymentProcessUseCase paymentProcessUseCase;

    @Mock
    private OrderedProductUseCase orderedProductUseCase;

    @Mock
    private PaymentCommandUseCase paymentCommandUseCase;

    @Mock
    private com.hyoguoo.paymentplatform.payment.application.port.PaymentProcessRepository paymentProcessRepository;

    @Nested
    @DisplayName("Step 1: 재고 차감 및 작업 생성 테스트")
    class ExecuteStockDecreaseWithJobCreationTest {

        @Test
        @DisplayName("재고 차감과 작업 생성을 성공적으로 수행한다")
        void executeSuccessfully() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            List<PaymentOrder> paymentOrderList = List.of(
                    createPaymentOrder(1L, 2)
            );
            PaymentProcess expectedProcess = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();

            given(paymentProcessUseCase.createProcessingJob(orderId))
                    .willReturn(expectedProcess);

            // when
            PaymentProcess result = coordinator.executeStockDecreaseWithJobCreation(
                    orderId,
                    paymentOrderList
            );

            // then
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.PROCESSING);

            then(orderedProductUseCase).should(times(1))
                    .decreaseStockForOrders(paymentOrderList);
            then(paymentProcessUseCase).should(times(1))
                    .createProcessingJob(orderId);
        }

        @Test
        @DisplayName("재고 부족 시 예외가 발생하고 작업이 생성되지 않는다")
        void throwExceptionWhenStockNotEnough() throws PaymentOrderedProductStockException {
            // given
            String orderId = "order-123";
            List<PaymentOrder> paymentOrderList = List.of(
                    createPaymentOrder(1L, 100)
            );

            org.mockito.BDDMockito.willThrow(PaymentOrderedProductStockException.of(PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH))
                    .given(orderedProductUseCase)
                    .decreaseStockForOrders(anyList());

            // when & then
            assertThatThrownBy(() -> coordinator.executeStockDecreaseWithJobCreation(
                    orderId,
                    paymentOrderList
            ))
                    .isInstanceOf(PaymentOrderedProductStockException.class);

            then(orderedProductUseCase).should(times(1))
                    .decreaseStockForOrders(paymentOrderList);
            then(paymentProcessUseCase).should(times(0))
                    .createProcessingJob(anyString());
        }
    }

    @Nested
    @DisplayName("Step 3-A: 결제 성공 처리 테스트")
    class ExecutePaymentSuccessCompletionTest {

        @Test
        @DisplayName("작업 완료와 결제 이벤트 완료 처리를 성공적으로 수행한다")
        void executeSuccessfully() {
            // given
            String orderId = "order-123";
            LocalDateTime approvedAt = LocalDateTime.now();
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            PaymentProcess completedProcess = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.COMPLETED)
                    .completedAt(approvedAt)
                    .allArgsBuild();
            PaymentEvent donePaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.DONE);

            given(paymentProcessUseCase.completeJob(orderId))
                    .willReturn(completedProcess);
            given(paymentCommandUseCase.markPaymentAsDone(any(PaymentEvent.class), any(LocalDateTime.class)))
                    .willReturn(donePaymentEvent);

            // when
            PaymentEvent result = coordinator.executePaymentSuccessCompletion(orderId, paymentEvent, approvedAt);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.DONE);

            then(paymentProcessUseCase).should(times(1))
                    .completeJob(orderId);
            then(paymentCommandUseCase).should(times(1))
                    .markPaymentAsDone(paymentEvent, approvedAt);
        }
    }

    @Nested
    @DisplayName("Step 3-B: 결제 실패 처리 테스트")
    class ExecutePaymentFailureCompensationTest {

        @Test
        @DisplayName("작업 실패, 재고 복구, 결제 이벤트 실패 처리를 성공적으로 수행한다")
        void executeSuccessfully() {
            // given
            String orderId = "order-123";
            String failureReason = "Payment timeout";
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            List<PaymentOrder> paymentOrderList = List.of(
                    createPaymentOrder(1L, 2)
            );
            PaymentProcess failedProcess = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.FAILED)
                    .failedAt(LocalDateTime.now())
                    .failureReason(failureReason)
                    .allArgsBuild();
            PaymentEvent failedPaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.FAILED);

            given(paymentProcessUseCase.existsByOrderId(orderId))
                    .willReturn(true);
            given(paymentProcessUseCase.failJob(orderId, failureReason))
                    .willReturn(failedProcess);
            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(failedPaymentEvent);

            // when
            PaymentEvent result = coordinator.executePaymentFailureCompensation(
                    orderId,
                    paymentEvent,
                    paymentOrderList,
                    failureReason
            );

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.FAILED);

            then(paymentProcessUseCase).should(times(1))
                    .existsByOrderId(orderId);
            then(paymentProcessUseCase).should(times(1))
                    .failJob(orderId, failureReason);
            then(orderedProductUseCase).should(times(1))
                    .increaseStockForOrders(paymentOrderList);
            then(paymentCommandUseCase).should(times(1))
                    .markPaymentAsFail(paymentEvent, failureReason);
        }

        @Test
        @DisplayName("재고 복구는 작업 실패 처리 후 수행된다")
        void verifyExecutionOrder() {
            // given
            String orderId = "order-123";
            String failureReason = "Payment failed";
            PaymentEvent paymentEvent = createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS);
            List<PaymentOrder> paymentOrderList = List.of(
                    createPaymentOrder(1L, 2)
            );
            PaymentProcess failedProcess = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.FAILED)
                    .allArgsBuild();
            PaymentEvent failedPaymentEvent = createPaymentEvent(orderId, PaymentEventStatus.FAILED);

            given(paymentProcessUseCase.existsByOrderId(anyString()))
                    .willReturn(true);
            given(paymentProcessUseCase.failJob(anyString(), anyString()))
                    .willReturn(failedProcess);
            given(paymentCommandUseCase.markPaymentAsFail(any(PaymentEvent.class), anyString()))
                    .willReturn(failedPaymentEvent);

            // when
            coordinator.executePaymentFailureCompensation(
                    orderId,
                    paymentEvent,
                    paymentOrderList,
                    failureReason
            );

            // then - 실행 순서 검증
            var inOrder = org.mockito.Mockito.inOrder(
                    paymentProcessUseCase,
                    orderedProductUseCase,
                    paymentCommandUseCase
            );
            inOrder.verify(paymentProcessUseCase).existsByOrderId(orderId);
            inOrder.verify(paymentProcessUseCase).failJob(orderId, failureReason);
            inOrder.verify(orderedProductUseCase).increaseStockForOrders(paymentOrderList);
            inOrder.verify(paymentCommandUseCase).markPaymentAsFail(paymentEvent, failureReason);
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
