package com.hyoguoo.paymentplatform.pg.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox Polling 안전망 워커.
 *
 * <p>ADR-04 대칭: payment-service 의 OutboxWorker 와 동격.
 * ADR-30: available_at 기반 지연 발행 — processedAt IS NULL AND availableAt <= NOW 조건 Polling.
 *
 * <p>역할:
 * <ul>
 *   <li>PgOutboxChannel 오버플로우 or ImmediateWorker 누락 row 안전망 처리.</li>
 *   <li>findPendingBatch(batchSize, now) 조회 → 각 row id 로 PgOutboxRelayService.relay(id).</li>
 *   <li>PgOutboxRelayService 내부에서 processedAt != null 이면 skip → exactly-once 보장.</li>
 * </ul>
 *
 * <p>실제 DB 환경에서는 JPA 구현체가 FOR UPDATE SKIP LOCKED 를 제공해야 한다 (T2a-04 JPA 어댑터).
 * Fake 환경에서는 FakePgOutboxRepository.findPendingBatch 가 동일 조건을 인메모리로 재현.
 */
@Slf4j
@Component
public class PgOutboxPollingWorker {

    // T-F2: ImmediateWorker 와 동일 카운터 이름 — Micrometer 태그로 출처 구분 가능하나 현 시점 단순화
    static final String RELAY_FAIL_COUNTER_NAME = "pg_outbox.relay_fail_total";

    private final PgOutboxRepository pgOutboxRepository;
    private final PgOutboxRelayService pgOutboxRelayService;
    private final Clock clock;
    private final Counter relayFailCounter;

    @Value("${pg.scheduler.polling-worker.batch-size:10}")
    private int batchSize;

    public PgOutboxPollingWorker(
            PgOutboxRepository pgOutboxRepository,
            PgOutboxRelayService pgOutboxRelayService,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.pgOutboxRepository = pgOutboxRepository;
        this.pgOutboxRelayService = pgOutboxRelayService;
        this.clock = clock;
        this.relayFailCounter = Counter.builder(RELAY_FAIL_COUNTER_NAME)
                .description("PgOutboxPollingWorker relay 실패 횟수")
                .register(meterRegistry);
    }

    /**
     * processedAt IS NULL AND availableAt <= NOW 조건의 pending row 를 polling 하여 relay 한다.
     * fixedDelay: 이전 실행 완료 후 지정 시간 대기 (과부하 방지).
     */
    @Scheduled(fixedDelayString = "${pg.scheduler.polling-worker.fixed-delay-ms:2000}")
    public void poll() {
        Instant now = Instant.now(clock);
        List<PgOutbox> pending = pgOutboxRepository.findPendingBatch(batchSize, now);

        if (pending.isEmpty()) {
            return;
        }

        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_POLLING_PENDING_FOUND,
                () -> "count=" + pending.size());
        for (PgOutbox outbox : pending) {
            try {
                pgOutboxRelayService.relay(outbox.getId());
            } catch (RuntimeException e) {
                // T-F2: Error 는 전파 — RuntimeException 만 포획 후 ERROR 승격 + 카운터 increment
                relayFailCounter.increment();
                LogFmt.error(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_POLLING_RELAY_FAIL,
                        () -> "id=" + outbox.getId() + " message=" + e.getMessage());
            }
        }
    }
}
