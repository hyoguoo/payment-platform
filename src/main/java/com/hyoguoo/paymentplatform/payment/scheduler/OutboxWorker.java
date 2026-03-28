package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWorker {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    @Value("${scheduler.outbox-worker.batch-size:10}")
    private int batchSize;

    @Value("${scheduler.outbox-worker.parallel-enabled:false}")
    private boolean parallelEnabled;

    @Value("${scheduler.outbox-worker.in-flight-timeout-minutes:5}")
    private int inFlightTimeoutMinutes;

    @Scheduled(fixedDelayString = "${scheduler.outbox-worker.fixed-delay-ms:5000}")
    public void process() {
        // Step 0: IN_FLIGHT 타임아웃 복구
        paymentOutboxUseCase.recoverTimedOutInFlightRecords(inFlightTimeoutMinutes);

        // Step 1: PENDING 배치 조회
        List<PaymentOutbox> pending = paymentOutboxUseCase.findPendingBatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        if (parallelEnabled) {
            processParallel(pending);
        } else {
            pending.forEach(this::processRecord);
        }
    }

    private void processParallel(List<PaymentOutbox> records) {
        // Java 21 가상 스레드: try-with-resources로 자동 종료 (awaitTermination 불필요)
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            records.forEach(record -> executor.submit(() -> processRecord(record)));
        }
    }

    private void processRecord(PaymentOutbox outbox) {
        // Step 2: PENDING → IN_FLIGHT (REQUIRES_NEW 트랜잭션, 즉시 커밋)
        boolean claimed = paymentOutboxUseCase.claimToInFlight(outbox);
        if (!claimed) {
            return;
        }

        String orderId = outbox.getOrderId();
        PaymentEvent paymentEvent = loadPaymentEvent(orderId, outbox);
        if (paymentEvent == null) {
            return;
        }

        try {
            // Step 3: Toss API 호출 (트랜잭션 밖)
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .userId(paymentEvent.getBuyerId())
                    .orderId(orderId)
                    .paymentKey(paymentEvent.getPaymentKey())
                    .amount(paymentEvent.getTotalAmount())
                    .build();
            PaymentGatewayInfo gatewayInfo = paymentCommandUseCase.confirmPaymentWithGateway(command);

            // Step 4-A: 성공 처리 (별도 트랜잭션)
            transactionCoordinator.executePaymentSuccessCompletionWithOutbox(
                    paymentEvent, gatewayInfo.getPaymentDetails().getApprovedAt(), outbox);

        } catch (PaymentTossNonRetryableException e) {
            // Step 4-B: 비재시도 실패 — 보상 트랜잭션
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                    paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);

        } catch (PaymentTossRetryableException e) {
            // Step 4-C: 재시도 가능 — retryCount 증가 또는 FAILED
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
        }
    }

    private PaymentEvent loadPaymentEvent(String orderId, PaymentOutbox outbox) {
        try {
            return paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            return null;
        }
    }
}
