package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentRecoveryUseCaseTest {

    @Mock
    private PaymentProcessUseCase paymentProcessUseCase;

    @Mock
    private PaymentLoadUseCase paymentLoadUseCase;

    @Mock
    private PaymentTransactionCoordinator transactionCoordinator;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @InjectMocks
    private PaymentRecoveryUseCase paymentRecoveryUseCase;

    @Test
    @DisplayName("PROCESSING 상태의 작업이 없으면 복구 작업을 수행하지 않는다")
    void recoverStuckPayments_NoProcessingJobs() {
        // given
        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of());

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(paymentGatewayPort, never()).getStatusByOrderId(anyString());
        verify(transactionCoordinator, never()).executePaymentSuccessCompletion(
                anyString(), any(), any()
        );
    }

    @Test
    @DisplayName("Toss에서 DONE 상태이면 작업 완료 처리한다")
    void recoverStuckPayments_TossStatusDone_CompletesJob() {
        // given
        String orderId = "order123";
        PaymentProcess processingJob = createPaymentProcess(orderId);
        PaymentEvent paymentEvent = createPaymentEvent(orderId);
        PaymentStatusResult statusResult = createPaymentStatusResult(PaymentStatus.DONE);

        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of(processingJob));
        when(paymentGatewayPort.getStatusByOrderId(orderId)).thenReturn(statusResult);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).thenReturn(paymentEvent);

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(transactionCoordinator, times(1)).executePaymentSuccessCompletion(
                orderId,
                paymentEvent,
                statusResult.approvedAt()
        );
        verify(transactionCoordinator, never()).executePaymentFailureCompensation(
                anyString(), any(), any(), anyString()
        );
    }

    @Test
    @DisplayName("Toss에서 CANCELED 상태이면 보상 트랜잭션을 실행한다")
    void recoverStuckPayments_TossStatusCanceled_ExecutesCompensation() {
        // given
        String orderId = "order123";
        PaymentProcess processingJob = createPaymentProcess(orderId);
        PaymentEvent paymentEvent = createPaymentEvent(orderId);
        PaymentStatusResult statusResult = createPaymentStatusResult(PaymentStatus.CANCELED);

        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of(processingJob));
        when(paymentGatewayPort.getStatusByOrderId(orderId)).thenReturn(statusResult);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).thenReturn(paymentEvent);

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(transactionCoordinator, times(1)).executePaymentFailureCompensation(
                orderId,
                paymentEvent,
                paymentEvent.getPaymentOrderList(),
                "CANCELED"
        );
        verify(transactionCoordinator, never()).executePaymentSuccessCompletion(
                anyString(), any(), any()
        );
    }

    @Test
    @DisplayName("Toss에서 ABORTED 상태이면 보상 트랜잭션을 실행한다")
    void recoverStuckPayments_TossStatusAborted_ExecutesCompensation() {
        // given
        String orderId = "order123";
        PaymentProcess processingJob = createPaymentProcess(orderId);
        PaymentEvent paymentEvent = createPaymentEvent(orderId);
        PaymentStatusResult statusResult = createPaymentStatusResult(PaymentStatus.ABORTED);

        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of(processingJob));
        when(paymentGatewayPort.getStatusByOrderId(orderId)).thenReturn(statusResult);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId)).thenReturn(paymentEvent);

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(transactionCoordinator, times(1)).executePaymentFailureCompensation(
                orderId,
                paymentEvent,
                paymentEvent.getPaymentOrderList(),
                "ABORTED"
        );
    }

    @Test
    @DisplayName("여러 개의 PROCESSING 작업을 순회하며 각각 복구한다")
    void recoverStuckPayments_MultipleJobs_RecoversAll() {
        // given
        String orderId1 = "order1";
        String orderId2 = "order2";
        PaymentProcess job1 = createPaymentProcess(orderId1);
        PaymentProcess job2 = createPaymentProcess(orderId2);

        PaymentEvent event1 = createPaymentEvent(orderId1);
        PaymentEvent event2 = createPaymentEvent(orderId2);

        PaymentStatusResult statusResult1 = createPaymentStatusResult(PaymentStatus.DONE);
        PaymentStatusResult statusResult2 = createPaymentStatusResult(PaymentStatus.CANCELED);

        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of(job1, job2));
        when(paymentGatewayPort.getStatusByOrderId(orderId1)).thenReturn(statusResult1);
        when(paymentGatewayPort.getStatusByOrderId(orderId2)).thenReturn(statusResult2);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId1)).thenReturn(event1);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId2)).thenReturn(event2);

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(transactionCoordinator, times(1)).executePaymentSuccessCompletion(
                orderId1, event1, statusResult1.approvedAt()
        );
        verify(transactionCoordinator, times(1)).executePaymentFailureCompensation(
                orderId2, event2, event2.getPaymentOrderList(), "CANCELED"
        );
    }

    @Test
    @DisplayName("한 작업 복구 실패 시에도 다른 작업은 계속 복구한다")
    void recoverStuckPayments_OneJobFails_ContinuesWithOthers() {
        // given
        String orderId1 = "order1";
        String orderId2 = "order2";
        PaymentProcess job1 = createPaymentProcess(orderId1);
        PaymentProcess job2 = createPaymentProcess(orderId2);

        PaymentEvent event2 = createPaymentEvent(orderId2);
        PaymentStatusResult statusResult2 = createPaymentStatusResult(PaymentStatus.DONE);

        when(paymentProcessUseCase.findAllProcessingJobs()).thenReturn(List.of(job1, job2));
        when(paymentGatewayPort.getStatusByOrderId(orderId1))
                .thenThrow(new RuntimeException("PG API error"));
        when(paymentGatewayPort.getStatusByOrderId(orderId2)).thenReturn(statusResult2);
        when(paymentLoadUseCase.getPaymentEventByOrderId(orderId2)).thenReturn(event2);

        // when
        paymentRecoveryUseCase.recoverStuckPayments();

        // then
        verify(transactionCoordinator, times(1)).executePaymentSuccessCompletion(
                orderId2, event2, statusResult2.approvedAt()
        );
    }

    private PaymentProcess createPaymentProcess(String orderId) {
        return PaymentProcess.allArgsBuilder()
                .orderId(orderId)
                .status(PaymentProcessStatus.PROCESSING)
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .quantity(1)
                .totalAmount(BigDecimal.valueOf(10000))
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .orderId(orderId)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("Test Order")
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(List.of(paymentOrder))
                .allArgsBuild();
    }

    private PaymentStatusResult createPaymentStatusResult(PaymentStatus status) {
        return new PaymentStatusResult(
                "test_key",
                "order123",
                status,
                BigDecimal.valueOf(10000),
                LocalDateTime.now(),
                null
        );
    }
}
