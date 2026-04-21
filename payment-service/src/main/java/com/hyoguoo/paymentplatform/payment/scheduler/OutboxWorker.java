package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxWorker {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final OutboxRelayService outboxRelayService;

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
            pending.forEach(outbox -> outboxRelayService.relay(outbox.getOrderId()));
        }
    }

    private void processParallel(List<PaymentOutbox> records) {
        // Java 21 가상 스레드: try-with-resources로 자동 종료 (awaitTermination 불필요)
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            records.forEach(record -> executor.submit(() -> outboxRelayService.relay(record.getOrderId())));
        }
    }
}
