package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("OutboxWorker 테스트")
class OutboxWorkerTest {

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private OutboxWorker outboxWorker;

    private static final String ORDER_ID = "order-123";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 12, 0, 0);

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);

        outboxWorker = new OutboxWorker(
                mockPaymentOutboxUseCase,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockTransactionCoordinator
        );
        ReflectionTestUtils.setField(outboxWorker, "batchSize", 10);
        ReflectionTestUtils.setField(outboxWorker, "parallelEnabled", false);
        ReflectionTestUtils.setField(outboxWorker, "inFlightTimeoutMinutes", 5);
    }

    @Test
    @DisplayName("process - PENDING 없음: findPendingBatch() 결과가 비면 Toss API 호출하지 않는다")
    void process_noPendingRecords_doesNotCallTossApi() throws Exception {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        outboxWorker.process();

        // then
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockPaymentOutboxUseCase).should(times(1)).recoverTimedOutInFlightRecords(5);
    }

    @Test
    @DisplayName("process - 정상 흐름: PENDING 레코드 → claimToInFlight → confirmPaymentWithGateway → executePaymentSuccessCompletion → markDone 순서로 호출")
    void process_pendingRecord_executesFullSuccessFlow() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(true);
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                anyString(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).claimToInFlight(pendingOutbox);
        then(mockPaymentCommandUseCase).should(times(1)).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletion(anyString(), any(PaymentEvent.class), any(LocalDateTime.class));
        then(mockPaymentOutboxUseCase).should(times(1)).markDone(ORDER_ID);
    }

    @Test
    @DisplayName("process - claimToInFlight false 반환: Toss API를 호출하지 않는다")
    void process_claimToInFlightReturnsFalse_doesNotCallTossApi() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(false);

        // when
        outboxWorker.process();

        // then
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockTransactionCoordinator).should(never()).executePaymentSuccessCompletion(any(), any(), any());
        then(mockPaymentOutboxUseCase).should(never()).markDone(anyString());
    }

    @Test
    @DisplayName("process - PaymentTossRetryableException: incrementRetryOrFail() 호출, executePaymentFailureCompensation() 호출 안 함")
    void process_retryableException_callsIncrementRetryOrFail() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(true);
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR));

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1))
                .incrementRetryOrFail(ORDER_ID, pendingOutbox);
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensation(any(), any(), any(), any());
        then(mockPaymentOutboxUseCase).should(never()).markFailed(anyString());
    }

    @Test
    @DisplayName("process - PaymentTossNonRetryableException: executePaymentFailureCompensation() + markFailed() 호출")
    void process_nonRetryableException_callsCompensationAndMarkFailed() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(true);
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR));
        given(mockTransactionCoordinator.executePaymentFailureCompensation(
                anyString(), any(PaymentEvent.class), any(), anyString()))
                .willReturn(paymentEvent);

        // when
        outboxWorker.process();

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensation(anyString(), any(PaymentEvent.class), any(), anyString());
        then(mockPaymentOutboxUseCase).should(times(1)).markFailed(ORDER_ID);
        then(mockPaymentOutboxUseCase).should(never()).incrementRetryOrFail(any(), any());
    }

    @Test
    @DisplayName("process - validateCompletionStatus 실패: confirmPaymentWithGateway()를 호출하지 않고 markFailed()를 호출한다")
    void process_validateCompletionStatusFails_callsMarkFailedAndNotGateway() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(true);
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        org.mockito.BDDMockito.willThrow(PaymentValidException.of(PaymentErrorCode.RETRYABLE_VALIDATION_ERROR))
                .given(mockPaymentCommandUseCase)
                .validateCompletionStatus(any(PaymentEvent.class), any(PaymentConfirmCommand.class));
        given(mockTransactionCoordinator.executePaymentFailureCompensation(
                anyString(), any(PaymentEvent.class), any(), anyString())).willReturn(paymentEvent);

        // when
        outboxWorker.process();

        // then
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockPaymentOutboxUseCase).should(times(1)).markFailed(ORDER_ID);
    }

    @Test
    @DisplayName("process - 정상 흐름: confirmPaymentWithGateway() 전에 validateCompletionStatus()를 호출한다")
    void process_pendingRecord_callsValidateCompletionStatusBeforeGateway() throws Exception {
        // given
        PaymentOutbox pendingOutbox = createPendingOutbox(ORDER_ID);
        PaymentEvent paymentEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo gatewayInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(List.of(pendingOutbox));
        given(mockPaymentOutboxUseCase.claimToInFlight(pendingOutbox)).willReturn(true);
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(gatewayInfo);
        given(mockTransactionCoordinator.executePaymentSuccessCompletion(
                anyString(), any(PaymentEvent.class), any(LocalDateTime.class)))
                .willReturn(paymentEvent);

        // when
        outboxWorker.process();

        // then
        var inOrder = Mockito.inOrder(mockPaymentCommandUseCase);
        inOrder.verify(mockPaymentCommandUseCase).validateCompletionStatus(
                any(PaymentEvent.class), any(PaymentConfirmCommand.class));
        inOrder.verify(mockPaymentCommandUseCase).confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
    }

    @Test
    @DisplayName("process - IN_FLIGHT 타임아웃 복구: process() 시작 시 recoverTimedOutInFlightRecords() 1회 호출")
    void process_alwaysCallsRecoverTimedOutInFlightRecords() throws Exception {
        // given
        given(mockPaymentOutboxUseCase.findPendingBatch(anyInt()))
                .willReturn(Collections.emptyList());

        // when
        outboxWorker.process();

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).recoverTimedOutInFlightRecords(5);
    }

    private PaymentOutbox createPendingOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .paymentKey("payment-key-123")
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentGatewayInfo createGatewayInfo(LocalDateTime approvedAt) {
        return PaymentGatewayInfo.builder()
                .paymentKey("payment-key-123")
                .orderId(ORDER_ID)
                .paymentDetails(
                        PaymentDetails.builder()
                                .approvedAt(approvedAt)
                                .totalAmount(BigDecimal.valueOf(10000))
                                .build()
                )
                .build();
    }
}
