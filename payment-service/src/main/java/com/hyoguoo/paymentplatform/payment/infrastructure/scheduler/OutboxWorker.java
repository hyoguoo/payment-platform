package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.payment.core.config.concurrent.ContextAwareVirtualThreadExecutors;
import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxWorker {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final OutboxRelayService outboxRelayService;
    private final int batchSize;
    private final boolean parallelEnabled;
    private final int inFlightTimeoutMinutes;

    public OutboxWorker(
            PaymentOutboxUseCase paymentOutboxUseCase,
            OutboxRelayService outboxRelayService,
            @Value("${scheduler.outbox-worker.batch-size:50}") int batchSize,
            @Value("${scheduler.outbox-worker.parallel-enabled:false}") boolean parallelEnabled,
            @Value("${scheduler.outbox-worker.in-flight-timeout-minutes:5}") int inFlightTimeoutMinutes) {
        this.paymentOutboxUseCase = paymentOutboxUseCase;
        this.outboxRelayService = outboxRelayService;
        this.batchSize = batchSize;
        this.parallelEnabled = parallelEnabled;
        this.inFlightTimeoutMinutes = inFlightTimeoutMinutes;
    }

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
            pending.forEach(outbox -> outboxRelayService.relay(outbox.getOrderId()));
        }
    }

    private void processParallel(List<PaymentOutbox> records) {
        // Java 21 가상 스레드 — try-with-resources 로 자동 종료(awaitTermination 불필요).
        // OTel Context + MDC 이중 래핑이 polling fallback 경로에서도 traceparent 를 그대로 전파한다.
        // 이중 래핑 boilerplate 는 ContextAwareVirtualThreadExecutors 헬퍼로 통일한다.
        try (ExecutorService executor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()) {
            records.forEach(record -> executor.submit(() -> outboxRelayService.relay(record.getOrderId())));
        }
    }
}
