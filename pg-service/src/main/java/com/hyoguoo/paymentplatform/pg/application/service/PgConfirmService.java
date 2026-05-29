package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.math.RoundingMode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * pg-service business inbox 상태 분기 오케스트레이터.
 * inbox 5상태 + 2단 멱등성 키(eventUUID + orderId) 적용.
 *
 * <p>분기 흐름:
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
 * <p>terminal 재발행은 {@link PgTerminalReemitService} 별도 빈으로 분리한다.
 * Spring proxy 를 경유해야 @Transactional self-invocation 우회 없이
 * TX 경계(pg_outbox save + publishEvent 동일 TX)를 보장할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgConfirmService implements PgConfirmCommandService {

    private final PgInboxRepository pgInboxRepository;
    private final PgVendorCallService pgVendorCallService;
    private final EventDedupeStore eventDedupeStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;
    private final PgInboxPendingService pgInboxPendingService;
    private final PgTerminalReemitService pgTerminalReemitService;

    @Override
    public void handle(PgConfirmCommand command, int attempt, String storedTraceparent) {
        // 1단계: eventUUID dedupe (메시지 레벨 멱등성)
        if (!eventDedupeStore.markSeen(command.eventUuid())) {
            LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_DUPLICATE_UUID,
                    () -> "eventUuid=" + command.eventUuid() + " orderId=" + command.orderId());
            return;
        }

        // TX 경계 불일치 방어: 처리 실패 시 dedupe 도 되돌려 재컨슘 경로에서 영구 정체를 방지한다.
        try {
            processCommand(command, attempt, storedTraceparent);
        } catch (RuntimeException e) {
            eventDedupeStore.remove(command.eventUuid());
            throw e;
        }
    }

    private void processCommand(PgConfirmCommand command, int attempt, String storedTraceparent) {
        // 2단계: inbox 상태 조회
        PgInbox inbox = pgInboxRepository.findByOrderId(command.orderId()).orElse(null);

        if (inbox == null) {
            // absent → listener TX 봉인 경로
            handleAbsent(command, storedTraceparent);
        } else if (inbox.getStatus() == PgInboxStatus.PENDING
                || inbox.getStatus() == PgInboxStatus.IN_PROGRESS) {
            // PENDING / IN_PROGRESS → 채널 재적재 (워커가 처리)
            handleActiveInbox(inbox);
        } else if (inbox.getStatus().isTerminal()) {
            // terminal 재수신 → stored_status_result 재발행
            // 외부 빈에 위임해 self-invocation @Transactional 우회를 피한다
            pgTerminalReemitService.reemit(inbox);
        }
    }

    // -----------------------------------------------------------------------
    // 내부 분기 메서드
    // -----------------------------------------------------------------------

    /**
     * inbox 없음 — listener TX 경계 봉인 경로.
     * {@link PgInboxPendingService#insertPendingAndPublish} 가 PENDING INSERT + publishEvent 를 단일 TX 안에서 수행.
     * listener 책임: INSERT + ack 까지. 워커 VT 풀이 채널에서 take 해 처리.
     *
     * <p>storedTraceparent 는 불투명 String 토큰으로만 전달 — OTel API import 없음.
     * traceparent 기록은 absent(신규 PENDING) 경로 한정.
     */
    private void handleAbsent(PgConfirmCommand command, String storedTraceparent) {
        long amountLong = command.amount().setScale(0, RoundingMode.UNNECESSARY).longValue();
        String vendorType = command.vendorType() != null ? command.vendorType().name() : null;

        pgInboxPendingService.insertPendingAndPublish(
                command.orderId(),
                amountLong,
                command.eventUuid(),
                vendorType,
                command.paymentKey(),
                storedTraceparent);

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_PENDING_INSERT,
                () -> "orderId=" + command.orderId() + " — PENDING INSERT + publishEvent 위임");
    }

    /**
     * PENDING / IN_PROGRESS inbox 존재 — 채널 재적재.
     * publishEvent(PgInboxReadyEvent) 로 채널에 재적재하고, 워커가 처리를 이어받는다.
     * IN_PROGRESS 자기 재진입(attempt >= 2) 도 이 경로로 처리되며, 워커가 IN_PROGRESS 좀비 경로를 밟는다.
     *
     * <p>inbox.getId() — JPA 어댑터 toDomain() 에서 DB pk 를 주입하므로 실제 환경에서는 non-null.
     * Fake 환경(단위 테스트)에서는 idIndex 로 관리하므로 id 가 없을 수 있음.
     */
    private void handleActiveInbox(PgInbox inbox) {
        applicationEventPublisher.publishEvent(new PgInboxReadyEvent(inbox.getId()));

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_IN_PROGRESS_RETRY,
                () -> "orderId=" + inbox.getOrderId() + " status=" + inbox.getStatus()
                        + " — 채널 재적재");
    }

}
