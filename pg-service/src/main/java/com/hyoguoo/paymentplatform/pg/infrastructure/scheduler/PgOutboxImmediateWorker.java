package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.core.config.concurrent.ContextAwareVirtualThreadExecutors;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.OutboxJob;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox 즉시 전달 워커.
 *
 * <p>payment-service 의 OutboxImmediateWorker 와 대칭 위치.
 * PgOutboxChannel.take() 로 outboxId 를 수신 → PgOutboxRelayService.relay(id) 위임.
 * KafkaTemplate 직접 호출 금지 — 반드시 PgOutboxRelayService(→ PgEventPublisherPort) 경유.
 *
 * <p>SmartLifecycle 골격 + 이중 scope 컨텍스트 복원은 {@link AbstractImmediateWorker} 가 담당한다.
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
public class PgOutboxImmediateWorker extends AbstractImmediateWorker<OutboxJob> {

    // relay 실패 전용 카운터 — ERROR 레벨 로그와 함께 관측성을 제공한다.
    static final String RELAY_FAIL_COUNTER_NAME = "pg_outbox.relay_fail_total";

    private final PgOutboxChannel channel;
    private final PgOutboxRelayService pgOutboxRelayService;
    private final int workerCount;
    private final Counter relayFailCounter;

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

    // ---- AbstractImmediateWorker 위임 ----

    @Override
    protected void initExecutor() {
        // OTel Context + MDC 이중 래핑이 payment.events.confirmed 발행 시 traceparent 를 정확히 전파한다.
        // 이중 래핑 boilerplate 는 ContextAwareVirtualThreadExecutors 헬퍼로 통일한다.
        relayExecutor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor();
    }

    @Override
    protected ExecutorService executor() {
        return relayExecutor;
    }

    @Override
    protected void shutdownExecutor() {
        awaitShutdown(relayExecutor);
    }

    @Override
    protected String workerNamePrefix() {
        return "pg-outbox-immediate-worker-";
    }

    @Override
    protected int workerCount() {
        return workerCount;
    }

    @Override
    protected OutboxJob takeJob() throws InterruptedException {
        return channel.take();
    }

    @Override
    protected void handle(OutboxJob job) {
        relay(job.outboxId());
    }

    @Override
    protected void logStarted(int count) {
        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_STARTED,
                () -> "workerCount=" + count);
    }

    @Override
    protected void logStopped() {
        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_STOPPED);
    }

    @Override
    protected void logLoopError(RuntimeException e) {
        // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그로 승격한다.
        LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_LOOP_ERROR,
                e::getMessage);
    }

    // ---- Outbox 전용 처리 ----

    private void relay(Long id) {
        try {
            pgOutboxRelayService.relay(id);
        } catch (RuntimeException e) {
            // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그 + 카운터 increment 로 승격한다.
            relayFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "id=" + id + " message=" + e.getMessage());
        }
    }
}
