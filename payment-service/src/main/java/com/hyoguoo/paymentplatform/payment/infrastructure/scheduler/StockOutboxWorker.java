package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.service.StockOutboxRelayService;
import com.hyoguoo.paymentplatform.payment.core.config.concurrent.ContextAwareVirtualThreadExecutors;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * stock_outbox 폴링 폴백 워커.
 * StockOutboxImmediateEventHandler (AFTER_COMMIT 리스너) 가 실패하거나 누락된 row 를 회수해 재발행한다.
 *
 * <p>OutboxWorker(payment_outbox) 와 동격이지만 stock_outbox 는 IN_FLIGHT 상태가 없어
 * timeout 회수 step 생략 — 단순히 processedAt IS NULL 인 PENDING 배치만 처리.
 *
 * <p>위험 회피: AFTER_COMMIT only 였을 때 Kafka 일시 장애 / VT executor 장애 시
 * stock_outbox PENDING 영구 잔존 → product RDB 재고 미차감 → 결제 DONE / 재고 발산
 * 패턴 방어.
 */
@Component
public class StockOutboxWorker {

    private final StockOutboxRepository stockOutboxRepository;
    private final StockOutboxRelayService stockOutboxRelayService;
    private final int batchSize;
    private final boolean parallelEnabled;

    public StockOutboxWorker(
            StockOutboxRepository stockOutboxRepository,
            StockOutboxRelayService stockOutboxRelayService,
            @Value("${scheduler.stock-outbox-worker.batch-size:50}") int batchSize,
            @Value("${scheduler.stock-outbox-worker.parallel-enabled:true}") boolean parallelEnabled) {
        this.stockOutboxRepository = stockOutboxRepository;
        this.stockOutboxRelayService = stockOutboxRelayService;
        this.batchSize = batchSize;
        this.parallelEnabled = parallelEnabled;
    }

    @Scheduled(fixedDelayString = "${scheduler.stock-outbox-worker.fixed-delay-ms:5000}")
    public void process() {
        List<StockOutbox> pending = stockOutboxRepository.findPendingBatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        if (parallelEnabled) {
            processParallel(pending);
        } else {
            pending.forEach(outbox -> stockOutboxRelayService.relay(outbox.getId()));
        }
    }

    private void processParallel(List<StockOutbox> records) {
        try (ExecutorService executor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()) {
            records.forEach(record -> executor.submit(() -> stockOutboxRelayService.relay(record.getId())));
        }
    }
}
