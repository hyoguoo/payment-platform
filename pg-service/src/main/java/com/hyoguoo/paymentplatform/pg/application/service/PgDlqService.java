package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayloadSerializer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service DLQ 전용 처리 서비스.
 * ADR-30(T2b-02): PaymentConfirmDlqConsumer로부터 위임받아 단일 TX 내에서
 * pg_inbox QUARANTINED 전이 + pg_outbox(payment.events.confirmed) row INSERT를 수행한다.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>pg_inbox FOR UPDATE 조회 (중복 DLQ 메시지 방어).</li>
 *   <li>이미 terminal(APPROVED/FAILED/QUARANTINED)이면 no-op (불변식 6c).</li>
 *   <li>그렇지 않으면 pg_inbox QUARANTINED 전이 + pg_outbox INSERT (같은 TX).</li>
 *   <li>TX commit 후 AFTER_COMMIT 이벤트 → T2a-05b/c 경로 재사용.</li>
 * </ol>
 *
 * <p>DLQ consumer 자체 실패 시 offset 미커밋 → 재기동 후 재처리.
 * pg_inbox UNIQUE + terminal 체크로 중복 방어 (불변식 6c).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgDlqService {

    private static final String REASON_RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;

    /**
     * DLQ 메시지를 처리한다.
     * 같은 TX 내에서 pg_inbox QUARANTINED 전이 + pg_outbox INSERT를 원자적으로 수행한다.
     *
     * @param command DLQ에서 수신한 PG 확정 커맨드
     */
    @Transactional
    public void handle(PgConfirmCommand command) {
        String orderId = command.orderId();

        // 1단계: FOR UPDATE 잠금 조회 (중복 DLQ 진입 방어)
        PgInbox inbox = pgInboxRepository.findByOrderIdForUpdate(orderId)
                .orElse(null);

        if (inbox == null) {
            log.warn("PgDlqService: inbox 없음 — no-op orderId={}", orderId);
            return;
        }

        // 2단계: terminal이면 no-op (불변식 6c 중복 DLQ 흡수)
        if (inbox.getStatus().isTerminal()) {
            log.info("PgDlqService: 이미 terminal — no-op (불변식 6c) orderId={} status={}",
                    orderId, inbox.getStatus());
            return;
        }

        // 3단계: pg_inbox QUARANTINED 전이 (compare-and-set)
        boolean transitioned = pgInboxRepository.transitToQuarantined(orderId, REASON_RETRY_EXHAUSTED);
        if (!transitioned) {
            // 다른 스레드/인스턴스가 이미 terminal 전이 — no-op
            log.info("PgDlqService: QUARANTINED 전이 실패(선점됨) — no-op orderId={}", orderId);
            return;
        }

        // 4단계: pg_outbox INSERT — topic=payment.events.confirmed, QUARANTINED 페이로드
        String payload = buildQuarantinedPayload(orderId, inbox.getAmount());
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);

        log.info("PgDlqService: QUARANTINED 전이 완료 + outbox INSERT orderId={} outboxId={}",
                orderId, saved.getId());

        // 5단계: TX commit 후 AFTER_COMMIT → PgOutboxImmediateWorker 경로 재사용 (T2a-05b/c)
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // payload 빌더
    // -----------------------------------------------------------------------

    private String buildQuarantinedPayload(String orderId, Long amount) {
        String eventUuid = UUID.randomUUID().toString();
        ConfirmedEventPayload payload = (amount != null)
                ? ConfirmedEventPayload.quarantinedWithAmount(orderId, REASON_RETRY_EXHAUSTED, amount, eventUuid)
                : ConfirmedEventPayload.quarantined(orderId, REASON_RETRY_EXHAUSTED, eventUuid);
        return payloadSerializer.serialize(payload);
    }
}
