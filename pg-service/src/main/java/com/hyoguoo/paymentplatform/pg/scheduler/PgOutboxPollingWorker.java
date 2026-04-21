package com.hyoguoo.paymentplatform.pg.scheduler;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
 *
 * <p>LogFmt 미사용: pg-service 는 별도 LogFmt 복제본을 갖지 않아 @Slf4j 평문 로깅.
 * TODO: T5-02 LogFmt 공통화 완결 단계에서 pg-service 전용 LogFmt 복제(또는 공통 모듈 분리) 적용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgOutboxPollingWorker {

    private final PgOutboxRepository pgOutboxRepository;
    private final PgOutboxRelayService pgOutboxRelayService;
    private final Clock clock;

    @Value("${pg.scheduler.polling-worker.batch-size:10}")
    private int batchSize;

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

        log.debug("PgOutboxPollingWorker: pending row 발견 count={}", pending.size());
        for (PgOutbox outbox : pending) {
            try {
                pgOutboxRelayService.relay(outbox.getId());
            } catch (Exception e) {
                log.warn("PgOutboxPollingWorker: relay 실패 id={} message={}", outbox.getId(), e.getMessage());
            }
        }
    }
}
