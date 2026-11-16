package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.InboxJob;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Inbox 즉시 처리 워커.
 *
 * <p>{@link PgOutboxImmediateWorker} 1:1 거울 위치 (발행 측 ↔ 수신 측).
 * {@link PgInboxChannel#poll} 로 inboxId 를 수신 → {@link PgInboxProcessUseCase#processPending(Long)} 위임.
 *
 * <p>SmartLifecycle 골격 + 이중 scope 컨텍스트 복원은 {@link AbstractImmediateWorker} 가 담당한다.
 *
 * <p>SmartLifecycle:
 * <ul>
 *   <li>start(): Virtual Thread 워커 N개(기본 64, 프로퍼티 pg.inbox.channel.worker-count) 기동.
 *       워커가 작업을 직접 처리하므로 N 이 벤더 confirm 동시 호출 상한이다.</li>
 *   <li>stop(): running=false 후 처리 중 작업을 최대 10초 기다리고, 남은 워커만 interrupt.</li>
 *   <li>getPhase(): Integer.MAX_VALUE - 100. Kafka 리스너 레지스트리와 같은 phase 라 둘의 종료 순서는 정해져 있지 않다.</li>
 * </ul>
 *
 * <p>VT 사용 — 벤더 HTTP 호출 latency 동안 캐리어 스레드를 양보하여 플랫폼 스레드 고갈을 방지한다.
 */
@Slf4j
@Component
public class PgInboxImmediateWorker extends AbstractImmediateWorker<InboxJob> {

    /**
     * 처리 실패 전용 카운터 이름 — ERROR 레벨 로그와 함께 관측성을 제공한다.
     * 워커 처리 실패 카운터의 단일 출처다.
     */
    static final String PROCESS_FAIL_COUNTER_NAME = "pg_inbox.process_fail_total";

    /**
     * 큐 대기가 정체 기준을 넘어 처리하지 않고 폴링 회수에 맡긴 작업 카운터 이름.
     */
    static final String STALE_SKIP_COUNTER_NAME = "pg_inbox.stale_job_skipped_total";

    private final PgInboxChannel channel;
    private final PgInboxProcessUseCase processor;
    private final PgInboxRepository inboxRepository;
    private final int workerCount;
    private final Duration staleAfter;
    private final Clock clock;
    private final Counter processFailCounter;
    private final Counter staleSkipCounter;

    public PgInboxImmediateWorker(
            PgInboxChannel channel,
            PgInboxProcessUseCase processor,
            PgInboxRepository inboxRepository,
            @Value("${pg.inbox.channel.worker-count:64}") int workerCount,
            @Value("${pg.scheduler.inbox-polling-worker.pending-timeout-ms:60000}") long pendingTimeoutMs,
            @Value("${pg.scheduler.inbox-polling-worker.in-progress-timeout-ms:60000}") long inProgressTimeoutMs,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.channel = channel;
        this.processor = processor;
        this.inboxRepository = inboxRepository;
        this.workerCount = workerCount;
        // 폴링 워커는 PENDING 을 created_at, IN_PROGRESS 를 updated_at 기준으로 회수한다. 둘 중 짧은 기준이
        // 지나면 폴링이 이미 그 행을 회수해 벤더를 부르고 있을 수 있으므로 짧은 쪽을 정체 기준으로 쓴다.
        // 건너뛴 작업은 상태에 맞는 기준이 지나면 폴링이 회수한다.
        this.staleAfter = Duration.ofMillis(Math.min(pendingTimeoutMs, inProgressTimeoutMs));
        this.clock = clock;
        this.processFailCounter = Counter.builder(PROCESS_FAIL_COUNTER_NAME)
                .description("PgInboxImmediateWorker 처리 실패 횟수")
                .register(meterRegistry);
        this.staleSkipCounter = Counter.builder(STALE_SKIP_COUNTER_NAME)
                .description("큐에서 정체 기준보다 오래 기다려 폴링 회수에 맡긴 작업 수")
                .register(meterRegistry);
    }

    // ---- AbstractImmediateWorker 위임 ----

    @Override
    protected String workerNamePrefix() {
        return "pg-inbox-immediate-worker-";
    }

    @Override
    protected int workerCount() {
        return workerCount;
    }

    @Override
    protected InboxJob pollJob(long timeout, TimeUnit unit) throws InterruptedException {
        return channel.poll(timeout, unit);
    }

    @Override
    protected void handle(InboxJob job) {
        // 큐에서 정체 기준 이상 기다린 작업은 그사이 폴링 워커가 같은 행을 회수해 벤더를 부르고 있을 수 있다.
        // 여기서 IN_PROGRESS 를 보고 정체 회수로 들어가면 벤더를 한 번 더 부르게 되므로 폴링에 맡긴다.
        Duration waited = Duration.between(job.enqueuedAt(), clock.instant());
        if (waited.compareTo(staleAfter) >= 0) {
            staleSkipCounter.increment();
            LogFmt.warn(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_SKIP,
                    () -> "inboxId=" + job.inboxId() + " waitedMs=" + waited.toMillis() + " reason=STALE_IN_QUEUE");
            return;
        }
        process(job.inboxId());
    }

    @Override
    protected void logStarted(int count) {
        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_STARTED,
                () -> "workerCount=" + count);
    }

    @Override
    protected void logStopped() {
        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_STOPPED);
    }

    @Override
    protected void logLoopError(Throwable e) {
        LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_LOOP_ERROR,
                e::getMessage);
    }

    // ---- Inbox 전용 처리 ----

    /**
     * inboxId 에 해당하는 row 상태를 조회해 분기 처리한다.
     *
     * <ul>
     *   <li>PENDING → {@code processor.processPending(inboxId)}</li>
     *   <li>IN_PROGRESS → {@code processor.processInProgressZombie(inboxId)} (워커 재진입 / 채널 재적재)</li>
     *   <li>terminal (APPROVED/FAILED/QUARANTINED) → skip + LogFmt info</li>
     *   <li>row 없음 → skip + LogFmt warn</li>
     * </ul>
     *
     * <p>inboxRepository.findById 는 조회 전용이므로 TX 영향이 없다.
     */
    private void process(Long inboxId) {
        try {
            Optional<PgInbox> inboxOpt = inboxRepository.findById(inboxId);
            if (inboxOpt.isEmpty()) {
                LogFmt.warn(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_SKIP,
                        () -> "inboxId=" + inboxId + " reason=NOT_FOUND");
                return;
            }
            PgInbox inbox = inboxOpt.get();
            PgInboxStatus status = inbox.getStatus();
            if (status == PgInboxStatus.PENDING) {
                processor.processPending(inboxId);
            } else if (status == PgInboxStatus.IN_PROGRESS) {
                processor.processInProgressZombie(inboxId);
            } else {
                // terminal (APPROVED / FAILED / QUARANTINED) — 이미 처리 완료, 채널 재적재 무시
                LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_SKIP,
                        () -> "inboxId=" + inboxId + " status=" + status + " reason=TERMINAL_SKIP");
            }
        } catch (RuntimeException | Error e) {
            // 워커 스레드가 작업을 직접 처리하므로 Error 를 전파하면 워커가 죽어 동시 처리 몫이 영구히 줄어든다.
            // Error 도 포획해 ERROR 로그 + 카운터로 남기고 다음 작업으로 넘어간다.
            processFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_FAIL,
                    () -> "inboxId=" + inboxId + " message=" + e.getMessage());
        }
    }
}
