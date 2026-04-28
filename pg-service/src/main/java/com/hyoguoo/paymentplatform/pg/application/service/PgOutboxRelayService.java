package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * pg-service Transactional Outbox relay.
 * PgEventPublisherPort 를 경유해서만 발행한다 — KafkaTemplate 직접 호출 금지.
 * pg_outbox 는 별도 status 컬럼 없이 processed_at 만 존재한다.
 * available_at 기반 지연 발행이고, 멱등성은 워커 루프(processed_at IS NULL AND available_at ≤ NOW) 가 담당한다.
 *
 * <p>relay(id) 처리 순서:
 * <ol>
 *   <li>findById — row 없으면 no-op 리턴.</li>
 *   <li>processedAt != null → 이미 발행된 row, no-op 리턴.</li>
 *   <li>availableAt > now → 아직 발행 불가, skip 리턴.</li>
 *   <li>publisher.publish() — 예외 발생 시 row 미갱신, 예외 전파(워커 다음 주기 재시도).</li>
 *   <li>성공 시 outbox.markProcessed(now) → repository.save().</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgOutboxRelayService {

    private final PgOutboxRepository pgOutboxRepository;
    private final PgEventPublisherPort pgEventPublisherPort;
    private final Clock clock;

    /**
     * outbox id에 해당하는 row를 Kafka로 릴레이한다.
     *
     * @param id pg_outbox PK
     */
    public void relay(long id) {
        Instant now = Instant.now(clock);

        Optional<PgOutbox> outboxOpt = pgOutboxRepository.findById(id);
        if (outboxOpt.isEmpty()) {
            LogFmt.debug(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_RELAY_NOT_FOUND,
                    () -> "id=" + id);
            return;
        }
        PgOutbox outbox = outboxOpt.get();

        // 이미 발행된 row — no-op
        if (outbox.getProcessedAt() != null) {
            LogFmt.debug(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_RELAY_ALREADY_PROCESSED,
                    () -> "id=" + id);
            return;
        }

        // available_at > now → skip
        if (!outbox.isAvailableAt(now)) {
            LogFmt.debug(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_RELAY_NOT_AVAILABLE_YET,
                    () -> "id=" + id + " availableAt=" + outbox.getAvailableAt());
            return;
        }

        // publish — 실패 시 예외 전파(row 미갱신)
        // Kafka 헤더는 spring.kafka.template.observation-enabled=true 가 publish 시점의
        // 현재 span 에서 traceparent 를 자동 주입한다. outbox row 의 headers_json 은
        // 향후 확장(예: attempt 카운터)을 위해 예약 필드이며 현 시점에는 사용하지 않는다.
        pgEventPublisherPort.publish(outbox.getTopic(), outbox.getKey(), outbox.getPayload(), Map.of());

        // 성공 시 processed_at 갱신
        outbox.markProcessed(now);
        pgOutboxRepository.save(outbox);

        LogFmt.info(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_RELAY_DONE,
                () -> "id=" + id + " topic=" + outbox.getTopic() + " key=" + outbox.getKey());
    }
}
