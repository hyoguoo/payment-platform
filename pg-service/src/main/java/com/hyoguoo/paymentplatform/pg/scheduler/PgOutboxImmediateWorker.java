package com.hyoguoo.paymentplatform.pg.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
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

    private final PgOutboxChannel channel;
    private final PgOutboxRelayService pgOutboxRelayService;
    private final int workerCount;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;
    private ExecutorService relayExecutor;

    public PgOutboxImmediateWorker(
            PgOutboxChannel channel,
            PgOutboxRelayService pgOutboxRelayService,
            @Value("${pg.outbox.channel.worker-count:1}") int workerCount
    ) {
        this.channel = channel;
        this.pgOutboxRelayService = pgOutboxRelayService;
        this.workerCount = workerCount;
    }

    @Override
    public void start() {
        running = true;
        relayExecutor = Executors.newVirtualThreadPerTaskExecutor();
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
            } catch (Exception e) {
                LogFmt.warn(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_LOOP_ERROR,
                        e::getMessage);
            }
        }
    }

    private void relay(Long id) {
        try {
            pgOutboxRelayService.relay(id);
        } catch (Exception e) {
            LogFmt.warn(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
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
