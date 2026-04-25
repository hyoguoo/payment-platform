package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.opentelemetry.context.Context;
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
        // T-J3: OTel Context + MDC 이중 래핑 — polling fallback 경로에서도 traceparent 정확히 propagate
        // Step 1: OTel Context 전파 (OTel ContextStorage 는 Micrometer ContextRegistry 와 별개)
        ExecutorService otelWrapped = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor());
        // Step 2: Micrometer ContextRegistry 등록 accessor(MDC 등) 전파
        try (ExecutorService executor = ContextExecutorService.wrap(
                otelWrapped, ContextSnapshotFactory.builder().build())) {
            records.forEach(record -> executor.submit(() -> outboxRelayService.relay(record.getOrderId())));
        }
    }
}
