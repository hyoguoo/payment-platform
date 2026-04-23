package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * pg-service business inbox 상태 분기 오케스트레이터.
 * ADR-21(inbox 5상태) + ADR-04(2단 멱등성 키) 적용.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>eventUUID dedupe — {@link EventDedupeStore#markSeen}: false면 즉시 no-op.</li>
 *   <li>inbox 상태 분기:
 *     <ul>
 *       <li>NONE → transitNoneToInProgress CAS → 성공 시 PG 호출 (T2b-01에서 실제 구현으로 확장)</li>
 *       <li>IN_PROGRESS → no-op (불변식 4b)</li>
 *       <li>APPROVED/FAILED/QUARANTINED → stored_status_result 재발행 (불변식 4/4b)</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>실제 PG 벤더 호출: {@link PgVendorCallService}에 위임 (T2b-01 구현 완료).
 * dry_run 모드(pg.retry.mode=dry_run): T2c-01 이후 확장 예정.
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

    @Override
    public void handle(PgConfirmCommand command) {
        // 1단계: eventUUID dedupe (메시지 레벨 멱등성 — 불변식 5)
        if (!eventDedupeStore.markSeen(command.eventUuid())) {
            LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_DUPLICATE_UUID,
                    () -> "eventUuid=" + command.eventUuid() + " orderId=" + command.orderId());
            return;
        }

        // TX 경계 불일치 방어: pg_outbox 저장/상태 전이가 롤백되면 dedupe도 되돌려
        // 재컨슘 경로에서 영구 정체를 방지한다.
        try {
            processCommand(command);
        } catch (RuntimeException e) {
            eventDedupeStore.remove(command.eventUuid());
            throw e;
        }
    }

    private void processCommand(PgConfirmCommand command) {
        // 2단계: inbox 상태 조회
        PgInbox inbox = pgInboxRepository.findByOrderId(command.orderId()).orElse(null);

        if (inbox == null || inbox.getStatus() == PgInboxStatus.NONE) {
            handleNone(command);
        } else if (inbox.getStatus() == PgInboxStatus.IN_PROGRESS) {
            handleInProgress(command.orderId());
        } else if (inbox.getStatus().isTerminal()) {
            handleTerminal(inbox);
        }
    }

    // -----------------------------------------------------------------------
    // 내부 분기 메서드
    // -----------------------------------------------------------------------

    private void handleNone(PgConfirmCommand command) {
        long amountLong = command.amount().setScale(0, RoundingMode.UNNECESSARY).longValue();

        boolean transitioned = pgInboxRepository.transitNoneToInProgress(command.orderId(), amountLong);
        if (!transitioned) {
            // 다른 스레드/인스턴스가 이미 선점 — IN_PROGRESS 경로로 위임
            LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_IN_PROGRESS_PREEMPTED,
                    () -> "orderId=" + command.orderId());
            return;
        }

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_NONE_TO_IN_PROGRESS,
                () -> "orderId=" + command.orderId());
        callVendor(command, 1);
    }

    private void handleInProgress(String orderId) {
        // IN_PROGRESS: 다른 소비자가 처리 중 — no-op 대기 (불변식 4b)
        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_IN_PROGRESS_NOOP,
                () -> "orderId=" + orderId);
    }

    private void handleTerminal(PgInbox inbox) {
        // terminal 재수신 → stored_status_result 로 pg_outbox 재발행 (벤더 재호출 금지 — 불변식 4/4b)
        String storedResult = inbox.getStoredStatusResult();
        if (storedResult == null || storedResult.isBlank()) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_CONFIRM_TERMINAL_REEMIT,
                    () -> "orderId=" + inbox.getOrderId() + " status=" + inbox.getStatus() + " — storedStatusResult 없음");
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

    private void callVendor(PgConfirmCommand command, int attempt) {
        PgConfirmRequest request = new PgConfirmRequest(
                command.orderId(),
                command.paymentKey(),
                command.amount(),
                command.vendorType());

        pgVendorCallService.callVendor(request, attempt, Instant.now(clock));

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_VENDOR_DELEGATED,
                () -> "orderId=" + command.orderId());
    }
}
