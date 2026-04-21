package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
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
 * ADR-04: PgEventPublisherPort를 경유해서만 발행 — KafkaTemplate 직접 호출 금지.
 * ADR-30: pg_outbox는 별도 status 컬럼 없이 processed_at만 존재.
 *         available_at 기반 지연 발행, 멱등성은 워커 루프(processed_at IS NULL AND available_at ≤ NOW)가 담당.
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
            log.debug("PgOutboxRelayService: outbox 없음 id={}", id);
            return;
        }
        PgOutbox outbox = outboxOpt.get();

        // 이미 발행된 row — no-op
        if (outbox.getProcessedAt() != null) {
            log.debug("PgOutboxRelayService: 이미 처리된 row id={}", id);
            return;
        }

        // available_at > now → skip
        if (!outbox.isAvailableAt(now)) {
            log.debug("PgOutboxRelayService: available_at 미도래 id={} availableAt={}", id, outbox.getAvailableAt());
            return;
        }

        // publish — 실패 시 예외 전파(row 미갱신)
        Map<String, byte[]> headers = parseHeaders(outbox.getHeadersJson());
        pgEventPublisherPort.publish(outbox.getTopic(), outbox.getKey(), outbox.getPayload(), headers);

        // 성공 시 processed_at 갱신
        outbox.markProcessed(now);
        pgOutboxRepository.save(outbox);

        log.info("PgOutboxRelayService: relay 완료 id={} topic={} key={}", id, outbox.getTopic(), outbox.getKey());
    }

    private Map<String, byte[]> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank() || headersJson.equals("{}")) {
            return Map.of();
        }
        // 헤더 JSON 파싱은 PgEventPublisher(infrastructure) 계층이 담당.
        // RelayService는 raw headersJson 문자열을 그대로 단일 헤더로 전달하지 않고,
        // 실제 Map 파싱은 T2a-05a 범위에서 단순 empty-map 처리로 제한한다.
        // (T2b 이후 실제 헤더 활용 시 ObjectMapper 주입으로 확장)
        return Map.of();
    }
}
