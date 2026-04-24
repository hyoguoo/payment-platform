package com.hyoguoo.paymentplatform.pg.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox 즉시 전달 워커.
 *
 * <p>ADR-04 대칭: payment-service 의 OutboxImmediateWorker 와 동격.
 * PgOutboxChannel.take() 로 outboxId 를 수신 → PgOutboxRelayService.relay(id) 위임.
 * KafkaTemplate 직접 호출 금지 — 반드시 PgOutboxRelayService(→ PgEventPublisherPort) 경유.
 *
 * <p>SmartLifecycle:
 * <ul>
 *   <li>start(): Virtual Thread 워커 스레드 N개(기본 1, 프로퍼티 pg.outbox.channel.worker-count) 기동.</li>
 *   <li>stop(): running=false + 스레드 interrupt + executor awaitTermination(10s).</li>
 *   <li>getPhase(): Integer.MAX_VALUE - 100 (채널보다 나중에 stop).</li>
 * </ul>
 */
@Slf4j
@Component
public class PgOutboxImmediateWorker implements SmartLifecycle {

    private static final int STOP_AWAIT_TIMEOUT_SECONDS = 10;
    // T-F2: relay 실패 전용 카운터 — ERROR 레벨 로그와 함께 관측성 제공
    static final String RELAY_FAIL_COUNTER_NAME = "pg_outbox.relay_fail_total";

    private final PgOutboxChannel channel;
    private final PgOutboxRelayService pgOutboxRelayService;
    private final int workerCount;
    private final Counter relayFailCounter;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;
    private ExecutorService relayExecutor;

    public PgOutboxImmediateWorker(
            PgOutboxChannel channel,
            PgOutboxRelayService pgOutboxRelayService,
            @Value("${pg.outbox.channel.worker-count:1}") int workerCount,
            MeterRegistry meterRegistry
    ) {
        this.channel = channel;
        this.pgOutboxRelayService = pgOutboxRelayService;
        this.workerCount = workerCount;
        this.relayFailCounter = Counter.builder(RELAY_FAIL_COUNTER_NAME)
                .description("PgOutboxImmediateWorker relay 실패 횟수")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        running = true;
        // T-E1: ContextExecutorService.wrap — VT 실행 시 MDC/OTel context 승계
        relayExecutor = ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(),
                ContextSnapshotFactory.builder().build()
        );
        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofVirtual()
                    .name("pg-outbox-immediate-worker-" + i)
                    .unstarted(this::workerLoop);
            workers.add(worker);
            worker.start();
        }
        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_STARTED,
                () -> "workerCount=" + workerCount);
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.forEach(worker -> {
            try {
                worker.join(STOP_AWAIT_TIMEOUT_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        workers.clear();
        shutdownRelayExecutor();
        callback.run();
        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_STOPPED);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 채널보다 나중에 stop되어 in-flight 항목이 drain될 수 있도록 높은 phase 값 사용
        return Integer.MAX_VALUE - 100;
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Long id = channel.take();
                relayExecutor.submit(() -> relay(id));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // T-F2: Error 는 전파 — RuntimeException 만 포획 후 ERROR 승격
                LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_LOOP_ERROR,
                        e::getMessage);
            }
        }
    }

    private void relay(Long id) {
        try {
            pgOutboxRelayService.relay(id);
        } catch (RuntimeException e) {
            // T-F2: Error 는 전파 — RuntimeException 만 포획 후 ERROR 승격 + 카운터 increment
            relayFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "id=" + id + " message=" + e.getMessage());
        }
    }

    private void shutdownRelayExecutor() {
        if (relayExecutor == null) {
            return;
        }
        relayExecutor.shutdown();
        try {
            if (!relayExecutor.awaitTermination(STOP_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                relayExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            relayExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
