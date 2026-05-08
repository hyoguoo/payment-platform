package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.math.RoundingMode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service business inbox 상태 분기 오케스트레이터 (PCS-9 재배치).
 * inbox 5상태 + 2단 멱등성 키(eventUUID + orderId) 적용.
 *
 * <p>PCS-9 분기 재배치:
 * <ol>
 *   <li>eventUUID dedupe — {@link EventDedupeStore#markSeen}: false면 즉시 no-op.</li>
 *   <li>inbox 상태 분기:
 *     <ul>
 *       <li>absent(inbox 없음) → {@link PgInboxPendingService#insertPendingAndPublish} (listener TX 봉인)</li>
 *       <li>PENDING → publishEvent(PgInboxReadyEvent) — 채널 재적재 (워커가 처리)</li>
 *       <li>IN_PROGRESS → publishEvent(PgInboxReadyEvent) — 채널 재적재 (워커가 처리)</li>
 *       <li>terminal(APPROVED/FAILED/QUARANTINED) → handleTerminal (@Transactional 봉인,
 *           stored_status_result 재발행)</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>§1.6 분기 재배치 + D-F3 흡수:
 * handleTerminal 에 {@code @Transactional} 명시 — pg_outbox save + publishEvent 가 동일 active TX 안에서
 * 실행되어야 {@code @TransactionalEventListener(AFTER_COMMIT)} 가 등록된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgConfirmService implements PgConfirmCommandService {

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final PgVendorCallService pgVendorCallService;
    private final EventDedupeStore eventDedupeStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;
    private final PgInboxPendingService pgInboxPendingService;

    @Override
    public void handle(PgConfirmCommand command, int attempt) {
        // 1단계: eventUUID dedupe (메시지 레벨 멱등성)
        if (!eventDedupeStore.markSeen(command.eventUuid())) {
            LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_DUPLICATE_UUID,
                    () -> "eventUuid=" + command.eventUuid() + " orderId=" + command.orderId());
            return;
        }

        // TX 경계 불일치 방어: 처리 실패 시 dedupe 도 되돌려 재컨슘 경로에서 영구 정체를 방지한다.
        try {
            processCommand(command, attempt);
        } catch (RuntimeException e) {
            eventDedupeStore.remove(command.eventUuid());
            throw e;
        }
    }

    private void processCommand(PgConfirmCommand command, int attempt) {
        // 2단계: inbox 상태 조회
        PgInbox inbox = pgInboxRepository.findByOrderId(command.orderId()).orElse(null);

        if (inbox == null) {
            // absent → listener TX 봉인 경로
            handleAbsent(command);
        } else if (inbox.getStatus() == PgInboxStatus.PENDING
                || inbox.getStatus() == PgInboxStatus.IN_PROGRESS) {
            // PENDING / IN_PROGRESS → 채널 재적재 (워커가 처리)
            handleActiveInbox(inbox);
        } else if (inbox.getStatus().isTerminal()) {
            // terminal 재수신 → stored_status_result 재발행 (@Transactional 봉인)
            handleTerminal(inbox);
        }
    }

    // -----------------------------------------------------------------------
    // 내부 분기 메서드
    // -----------------------------------------------------------------------

    /**
     * inbox 없음 — listener TX 경계 봉인 경로.
     * {@link PgInboxPendingService#insertPendingAndPublish} 가 PENDING INSERT + publishEvent 를 단일 TX 안에서 수행.
     * listener 책임: INSERT + ack 까지. 워커 VT 풀이 채널에서 take 해 처리.
     */
    private void handleAbsent(PgConfirmCommand command) {
        long amountLong = command.amount().setScale(0, RoundingMode.UNNECESSARY).longValue();
        String vendorType = command.vendorType() != null ? command.vendorType().name() : null;

        pgInboxPendingService.insertPendingAndPublish(
                command.orderId(),
                amountLong,
                command.eventUuid(),
                vendorType,
                command.paymentKey());

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_NONE_TO_IN_PROGRESS,
                () -> "orderId=" + command.orderId() + " — PENDING INSERT + publishEvent 위임");
    }

    /**
     * PENDING / IN_PROGRESS inbox 존재 — 채널 재적재.
     * publishEvent(PgInboxReadyEvent) 로 채널에 재적재하고, 워커가 처리를 이어받는다.
     * IN_PROGRESS 자기 재진입(attempt >= 2) 도 이 경로로 처리되며, 워커가 IN_PROGRESS 좀비 경로를 밟는다.
     */
    private void handleActiveInbox(PgInbox inbox) {
        applicationEventPublisher.publishEvent(new PgInboxReadyEvent(null));

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_IN_PROGRESS_RETRY,
                () -> "orderId=" + inbox.getOrderId() + " status=" + inbox.getStatus()
                        + " — 채널 재적재");
    }

    /**
     * terminal 재수신 — stored_status_result 재발행.
     * D-F3 흡수: {@code @Transactional} 명시 — pg_outbox save + publishEvent 가 동일 active TX 안에서
     * 실행되어야 {@code @TransactionalEventListener(AFTER_COMMIT)} 가 등록된다.
     * TX 없으면 JpaRepository.save 가 자체 TX 로 즉시 커밋 → 후속 publishEvent 는 active TX 외부
     * → AFTER_COMMIT 미등록 → 채널 적재 0.
     */
    @Transactional
    protected void handleTerminal(PgInbox inbox) {
        String storedResult = inbox.getStoredStatusResult();
        if (storedResult == null || storedResult.isBlank()) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_CONFIRM_TERMINAL_REEMIT,
                    () -> "orderId=" + inbox.getOrderId()
                            + " status=" + inbox.getStatus()
                            + " — storedStatusResult 없음");
            return;
        }

        PgOutbox reemit = PgOutbox.create(
                null,
                PgTopics.EVENTS_CONFIRMED,
                inbox.getOrderId(),
                storedResult,
                null);
        PgOutbox saved = pgOutboxRepository.save(reemit);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_TERMINAL_REEMIT,
                () -> "orderId=" + inbox.getOrderId() + " status=" + inbox.getStatus());
    }
}
