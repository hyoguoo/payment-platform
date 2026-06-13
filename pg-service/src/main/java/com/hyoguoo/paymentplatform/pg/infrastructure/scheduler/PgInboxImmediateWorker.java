package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.core.config.concurrent.ContextAwareVirtualThreadExecutors;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.InboxJob;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Inbox 즉시 처리 워커.
 *
 * <p>{@link PgOutboxImmediateWorker} 1:1 거울 위치 (발행 측 ↔ 수신 측).
 * {@link PgInboxChannel#take()} 로 inboxId 를 수신 → {@link PgInboxProcessUseCase#processPending(Long)} 위임.
 *
 * <p>SmartLifecycle 골격 + 이중 scope 컨텍스트 복원은 {@link AbstractImmediateWorker} 가 담당한다.
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
public class PgInboxImmediateWorker extends AbstractImmediateWorker<InboxJob> {

    /**
     * 처리 실패 전용 카운터 이름 — ERROR 레벨 로그와 함께 관측성을 제공한다.
     * 워커 처리 실패 카운터의 단일 출처다.
     */
    static final String PROCESS_FAIL_COUNTER_NAME = "pg_inbox.process_fail_total";

    private final PgInboxChannel channel;
    private final PgInboxProcessUseCase processor;
    private final PgInboxRepository inboxRepository;
    private final int workerCount;
    private final Counter processFailCounter;

    private ExecutorService processExecutor;

    public PgInboxImmediateWorker(
            PgInboxChannel channel,
            PgInboxProcessUseCase processor,
            PgInboxRepository inboxRepository,
            @Value("${pg.inbox.channel.worker-count:5}") int workerCount,
            MeterRegistry meterRegistry
    ) {
        this.channel = channel;
        this.processor = processor;
        this.inboxRepository = inboxRepository;
        this.workerCount = workerCount;
        this.processFailCounter = Counter.builder(PROCESS_FAIL_COUNTER_NAME)
                .description("PgInboxImmediateWorker 처리 실패 횟수")
                .register(meterRegistry);
    }

    // ---- AbstractImmediateWorker 위임 ----

    @Override
    protected void initExecutor() {
        // OTel Context + MDC 이중 래핑 — offer 시점(Kafka consumer thread)의 traceparent 를
        // worker VT thread 에 정확히 전파한다. 이중 래핑 boilerplate 는
        // ContextAwareVirtualThreadExecutors 헬퍼로 통일한다.
        processExecutor = ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor();
    }

    @Override
    protected ExecutorService executor() {
        return processExecutor;
    }

    @Override
    protected void shutdownExecutor() {
        awaitShutdown(processExecutor);
    }

    @Override
    protected String workerNamePrefix() {
        return "pg-inbox-immediate-worker-";
    }

    @Override
    protected int workerCount() {
        return workerCount;
    }

    @Override
    protected InboxJob takeJob() throws InterruptedException {
        return channel.take();
    }

    @Override
    protected void handle(InboxJob job) {
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
    protected void logLoopError(RuntimeException e) {
        // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그로 승격한다.
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
        } catch (RuntimeException e) {
            // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그 + 카운터 increment 로 승격한다.
            processFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_WORKER_FAIL,
                    () -> "inboxId=" + inboxId + " message=" + e.getMessage());
        }
    }
}
