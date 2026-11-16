package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.OutboxJob;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox 즉시 전달 워커.
 *
 * <p>payment-service 의 OutboxImmediateWorker 와 대칭 위치.
 * PgOutboxChannel.poll() 로 outboxId 를 수신 → PgOutboxRelayService.relay(id) 위임.
 * KafkaTemplate 직접 호출 금지 — 반드시 PgOutboxRelayService(→ PgEventPublisherPort) 경유.
 *
 * <p>SmartLifecycle 골격 + 이중 scope 컨텍스트 복원은 {@link AbstractImmediateWorker} 가 담당한다.
 *
 * <p>SmartLifecycle:
 * <ul>
 *   <li>start(): Virtual Thread 워커 스레드 N개(기본 8, 프로퍼티 pg.outbox.channel.worker-count) 기동.
 *       워커가 작업을 직접 처리하므로 N 이 동시 발행 상한이다.</li>
 *   <li>stop(): running=false 후 처리 중 작업을 최대 10초 기다리고, 남은 워커만 interrupt.</li>
 *   <li>getPhase(): Integer.MAX_VALUE - 100. Kafka 리스너 레지스트리와 같은 phase 라 둘의 종료 순서는 정해져 있지 않다.</li>
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

    public PgOutboxImmediateWorker(
            PgOutboxChannel channel,
            PgOutboxRelayService pgOutboxRelayService,
            @Value("${pg.outbox.channel.worker-count:8}") int workerCount,
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
    protected String workerNamePrefix() {
        return "pg-outbox-immediate-worker-";
    }

    @Override
    protected int workerCount() {
        return workerCount;
    }

    @Override
    protected OutboxJob pollJob(long timeout, TimeUnit unit) throws InterruptedException {
        return channel.poll(timeout, unit);
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
    protected void logLoopError(Throwable e) {
        LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_LOOP_ERROR,
                e::getMessage);
    }

    // ---- Outbox 전용 처리 ----

    private void relay(Long id) {
        try {
            pgOutboxRelayService.relay(id);
        } catch (RuntimeException | Error e) {
            // 워커 스레드가 작업을 직접 처리하므로 Error 를 전파하면 워커가 하나씩 죽어 즉시 발행 용량이 줄어든다.
            // Error 도 포획해 ERROR 로그 + 카운터로 남기고 다음 작업으로 넘어간다.
            relayFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_WORKER_RELAY_FAIL,
                    () -> "id=" + id + " message=" + e.getMessage());
        }
    }
}
