package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Inbox 좀비 회수 폴링 워커.
 *
 * <p>{@link PgOutboxPollingWorker} 와 대칭 위치 (발행 측 ↔ 수신 측).
 *
 * <p>역할:
 * <ul>
 *   <li>PENDING 좀비 — {@code received_at < now - pendingTimeoutMs} 조건. 채널 적재 실패 / 워커 take 실패로
 *       IN_PROGRESS 전이가 이뤄지지 않은 row 를 직접 {@link PgInboxProcessUseCase#processPending(Long)} 위임.</li>
 *   <li>IN_PROGRESS 좀비 — {@code updated_at < now - inProgressTimeoutMs} 조건. 워커 크래시 / TX_B 실패로
 *       terminal 전이가 이뤄지지 않은 row 를 {@link PgInboxProcessUseCase#processInProgressZombie(Long)} 위임.</li>
 * </ul>
 *
 * <p>새 root span: 폴링 워커는 원 Kafka 메시지의 traceparent 와 연결하지 않고 새 OTel root span 으로 시작한다.
 * 이는 의도적인 설계 결정으로, 복구 트레이스를 기존 비즈니스 트레이스와 분리한다.
 *
 * <p>race window: PENDING → IN_PROGRESS / IN_PROGRESS → terminal 전이의 SELECT FOR UPDATE SKIP LOCKED 는
 * {@link PgInboxRepository} 구현체 계층 책임이다. 본 워커는 호출만 위임한다.
 */
@Slf4j
@Component
public class PgInboxPollingWorker {

    /**
     * 좀비 처리 실패 전용 카운터 이름.
     * 처리 중 RuntimeException 발생 시 ERROR 로그와 함께 관측성을 제공한다.
     */
    static final String ZOMBIE_FAIL_COUNTER_NAME = "pg_inbox.zombie_fail_total";

    /**
     * 좀비 회수 성공 카운터 이름.
     * status 태그 (PENDING / IN_PROGRESS) 로 두 경로를 구분한다.
     * {@code pg_inbox.zombie_recovered_total{status=PENDING|IN_PROGRESS}}.
     */
    static final String ZOMBIE_RECOVERED_COUNTER_NAME = "pg_inbox.zombie_recovered_total";

    private static final String STATUS_TAG_PENDING = "PENDING";
    private static final String STATUS_TAG_IN_PROGRESS = "IN_PROGRESS";

    private final PgInboxRepository inboxRepository;
    private final PgInboxProcessUseCase processor;
    private final Counter zombieFailCounter;
    private final Counter zombieRecoveredPendingCounter;
    private final Counter zombieRecoveredInProgressCounter;
    private final int batchSize;
    private final long pendingTimeoutMs;
    private final long inProgressTimeoutMs;

    public PgInboxPollingWorker(
            PgInboxRepository inboxRepository,
            PgInboxProcessUseCase processor,
            @Value("${pg.scheduler.inbox-polling-worker.batch-size:10}") int batchSize,
            @Value("${pg.scheduler.inbox-polling-worker.pending-timeout-ms:60000}") long pendingTimeoutMs,
            @Value("${pg.scheduler.inbox-polling-worker.in-progress-timeout-ms:60000}") long inProgressTimeoutMs,
            MeterRegistry meterRegistry
    ) {
        this.inboxRepository = inboxRepository;
        this.processor = processor;
        this.batchSize = batchSize;
        this.pendingTimeoutMs = pendingTimeoutMs;
        this.inProgressTimeoutMs = inProgressTimeoutMs;
        this.zombieFailCounter = Counter.builder(ZOMBIE_FAIL_COUNTER_NAME)
                .description("PgInboxPollingWorker 좀비 처리 실패 횟수")
                .register(meterRegistry);
        this.zombieRecoveredPendingCounter = Counter.builder(ZOMBIE_RECOVERED_COUNTER_NAME)
                .description("PgInboxPollingWorker 좀비 회수 성공 횟수 (PENDING 경로)")
                .tag("status", STATUS_TAG_PENDING)
                .register(meterRegistry);
        this.zombieRecoveredInProgressCounter = Counter.builder(ZOMBIE_RECOVERED_COUNTER_NAME)
                .description("PgInboxPollingWorker 좀비 회수 성공 횟수 (IN_PROGRESS 경로)")
                .tag("status", STATUS_TAG_IN_PROGRESS)
                .register(meterRegistry);
    }

    /**
     * PENDING / IN_PROGRESS 두 경로의 좀비 row 를 회수한다.
     *
     * <p>fixedDelay: 이전 실행 완료 후 지정 시간 대기 (과부하 방지).
     * 복구 트레이스를 기존 트레이스와 분리하기 위해 새 root span 으로 시작한다.
     */
    @Scheduled(fixedDelayString = "${pg.scheduler.inbox-polling-worker.fixed-delay-ms:5000}")
    public void poll() {
        recoverPendingZombies();
        recoverInProgressZombies();
    }

    private void recoverPendingZombies() {
        List<Long> pendingZombieIds = inboxRepository.findPendingZombieIds(batchSize, pendingTimeoutMs);

        if (pendingZombieIds.isEmpty()) {
            return;
        }

        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_POLLING_PENDING_FOUND,
                () -> "count=" + pendingZombieIds.size());

        for (Long inboxId : pendingZombieIds) {
            processSafely(() -> processor.processPending(inboxId), inboxId, STATUS_TAG_PENDING,
                    zombieRecoveredPendingCounter, EventType.PG_INBOX_ZOMBIE_RECOVERED_PENDING);
        }
    }

    private void recoverInProgressZombies() {
        List<Long> inProgressZombieIds = inboxRepository.findInProgressZombieIds(batchSize, inProgressTimeoutMs);

        if (inProgressZombieIds.isEmpty()) {
            return;
        }

        LogFmt.info(log, LogDomain.PG_INBOX, EventType.PG_INBOX_POLLING_IN_PROGRESS_FOUND,
                () -> "count=" + inProgressZombieIds.size());

        for (Long inboxId : inProgressZombieIds) {
            processSafely(() -> processor.processInProgressZombie(inboxId), inboxId, STATUS_TAG_IN_PROGRESS,
                    zombieRecoveredInProgressCounter, EventType.PG_INBOX_ZOMBIE_RECOVERED_IN_PROGRESS);
        }
    }

    /**
     * 좀비 처리 단건 — 성공 시 회수 카운터 + 로그, 실패 시 실패 카운터 + ERROR 로그.
     *
     * @param action           처리 액션 (processPending / processInProgressZombie)
     * @param inboxId          처리 대상 inbox id
     * @param zombieStatus     로그 태그 (PENDING / IN_PROGRESS)
     * @param recoveredCounter 회수 성공 카운터
     * @param recoveredEvent   회수 성공 EventType (LogFmt info용)
     */
    private void processSafely(Runnable action, Long inboxId, String zombieStatus,
                               Counter recoveredCounter, EventType recoveredEvent) {
        try {
            action.run();
            // 성공 시 회수 카운터 increment + LogFmt info emit
            recoveredCounter.increment();
            LogFmt.info(log, LogDomain.PG_INBOX, recoveredEvent,
                    () -> "inboxId=" + inboxId + " status=" + zombieStatus);
        } catch (RuntimeException e) {
            // Error 는 전파하고 RuntimeException 만 포획해 ERROR 로그 + 카운터 increment 로 승격한다.
            zombieFailCounter.increment();
            LogFmt.error(log, LogDomain.PG_INBOX, EventType.PG_INBOX_POLLING_ZOMBIE_FAIL,
                    () -> "inboxId=" + inboxId + " status=" + zombieStatus + " message=" + e.getMessage());
        }
    }
}
