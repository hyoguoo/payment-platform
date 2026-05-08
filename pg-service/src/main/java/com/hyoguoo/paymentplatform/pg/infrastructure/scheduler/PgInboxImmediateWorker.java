package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.core.config.concurrent.ContextAwareVirtualThreadExecutors;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.InboxJob;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Inbox 즉시 처리 워커.
 *
 * <p>{@link PgOutboxImmediateWorker} 1:1 거울 위치 (발행 측 ↔ 수신 측).
 * {@link PgInboxChannel#take()} 로 inboxId 를 수신 → {@link PgInboxProcessUseCase#processPending(Long)} 위임.
 *
 * <p>SmartLifecycle:
 * <ul>
 *   <li>start(): Virtual Thread 워커 N개(기본 5, 프로퍼티 pg.inbox.channel.worker-count) 기동.</li>
 *   <li>stop(): running=false + 스레드 interrupt + join(10s) + executor shutdown.</li>
 *   <li>getPhase(): Integer.MAX_VALUE - 100 (채널보다 나중에 stop).</li>
 * </ul>
 *
 * <p>VT 사용 — {@code Executors.newVirtualThreadPerTaskExecutor()}.
 * 벤더 HTTP 호출 latency 동안 캐리어 스레드를 양보하여 플랫폼 스레드 고갈을 방지한다.
 */
@Slf4j
@Component
public class PgInboxImmediateWorker implements SmartLifecycle {

    private static final int STOP_AWAIT_TIMEOUT_SECONDS = 10;

    /**
     * 처리 실패 전용 카운터 이름 — ERROR 레벨 로그와 함께 관측성을 제공한다.
     * PCS-14 SoT (PC-F6 흡수) — pg_inbox.process_fail_total.
     */
    static final String PROCESS_FAIL_COUNTER_NAME = "pg_inbox.process_fail_total";

    private final PgInboxChannel channel;
    private final PgInboxProcessUseCase processor;
    private final int workerCount;
    private final Counter processFailCounter;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;
    private ExecutorService processExecutor;

    public PgInboxImmediateWorker(
            PgInboxChannel channel,
            PgInboxProcessUseCase processor,
            @Value("${pg.inbox.channel.worker-count:5}") int workerCount,
            MeterRegistry meterRegistry
    ) {
        this.channel = channel;
        this.processor = processor;
        this.workerCount = workerCount;
        this.processFailCounter = Counter.builder(PROCESS_FAIL_COUNTER_NAME)
                .description("PgInboxImmediateWorker 처리 실패 횟수")
                .register(meterRegistry);
    }

    @Override
    public void start() {
        running = true;
        // OTel Context + MDC 이중 래핑 — offer 시점(Kafka consumer thread)의 traceparent 를
        // worker VT thread 에 정확히 전파한다. 이중 래핑 boilerplate 는
        // ContextAwareVirtualThreadExecutors 헬퍼로 통일한다.
        processExecutor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor();
        for (int i = 0; i < workerCount; i++) {
            Thread worker = Thread.ofVirtual()
                    .name("pg-inbox-immediate-worker-" + i)
                    .unstarted(this::workerLoop);
            workers.add(worker);
            worker.start();
        }
        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_STARTED,
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
        shutdownProcessExecutor();
        callback.run();
        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_STOPPED);
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
                InboxJob job = channel.take();
                // processExecutor.submit lambda 에서 offer 시점(Kafka consumer thread)의
                // OTel Context + MDC snapshot 을 restore — worker VT thread 의 빈 context 를 덮어쓴다.
                // try-with-resources 이중 scope: MDC(Micrometer) → OTel Context 순으로 열고
                // 역순으로 닫아 traceparent 가 processPending 내부까지 정확히 전파된다.
                processExecutor.submit(() -> processWithContext(job));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그로 승격한다.
                LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_LOOP_ERROR,
                        e::getMessage);
            }
        }
    }

    private void processWithContext(InboxJob job) {
        try (
                ContextSnapshot.Scope mdcScope = job.snapshot().setThreadLocals();
                Scope otelScope = job.otelContext().makeCurrent()
        ) {
            process(job.inboxId());
        }
    }

    private void process(Long inboxId) {
        try {
            processor.processPending(inboxId);
        } catch (RuntimeException e) {
            // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그 + 카운터 increment 로 승격한다.
            processFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_FAIL,
                    () -> "inboxId=" + inboxId + " message=" + e.getMessage());
        }
    }

    private void shutdownProcessExecutor() {
        if (processExecutor == null) {
            return;
        }
        processExecutor.shutdown();
        try {
            if (!processExecutor.awaitTermination(STOP_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                processExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            processExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
