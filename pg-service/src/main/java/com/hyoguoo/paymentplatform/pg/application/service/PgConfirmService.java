package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgGatewayPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>실제 PG 벤더 호출 확장: T2b-01에서 구현. 현재는 {@link PgGatewayPort#confirm} 직접 호출.
 * dry_run 모드(pg.retry.mode=dry_run): T2c-01 이후 확장 예정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgConfirmService implements PgConfirmCommandService {

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final PgGatewayPort pgGatewayPort;
    private final EventDedupeStore eventDedupeStore;

    @Override
    public void handle(PgConfirmCommand command) {
        // 1단계: eventUUID dedupe (메시지 레벨 멱등성 — 불변식 5)
        if (!eventDedupeStore.markSeen(command.eventUuid())) {
            log.info("PgConfirmService: 중복 eventUUID 감지 — 무시 eventUuid={} orderId={}",
                    command.eventUuid(), command.orderId());
            return;
        }

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
            log.info("PgConfirmService: IN_PROGRESS 전이 실패(선점됨) — no-op orderId={}", command.orderId());
            return;
        }

        log.info("PgConfirmService: NONE→IN_PROGRESS 전이 성공 — PG 호출 시작 orderId={}", command.orderId());
        callVendor(command);
    }

    private void handleInProgress(String orderId) {
        // IN_PROGRESS: 다른 소비자가 처리 중 — no-op 대기 (불변식 4b)
        log.info("PgConfirmService: IN_PROGRESS 상태 재수신 — no-op orderId={}", orderId);
    }

    private void handleTerminal(PgInbox inbox) {
        // terminal 재수신 → stored_status_result 로 pg_outbox 재발행 (벤더 재호출 금지 — 불변식 4/4b)
        String storedResult = inbox.getStoredStatusResult();
        if (storedResult == null || storedResult.isBlank()) {
            log.warn("PgConfirmService: terminal 상태지만 storedStatusResult 없음 orderId={} status={}",
                    inbox.getOrderId(), inbox.getStatus());
            return;
        }

        PgOutbox reemit = PgOutbox.create(
                null,
                PgTopics.EVENTS_CONFIRMED,
                inbox.getOrderId(),
                storedResult,
                null);
        pgOutboxRepository.save(reemit);

        log.info("PgConfirmService: terminal 재발행 orderId={} status={}",
                inbox.getOrderId(), inbox.getStatus());
    }

    private void callVendor(PgConfirmCommand command) {
        // T2b-01에서 실제 재시도 루프 + available_at 지연 재발행으로 확장 예정.
        // 현재는 PgGatewayPort.confirm() 직접 호출 (placeholder).
        PgConfirmRequest request = new PgConfirmRequest(
                command.orderId(),
                command.paymentKey(),
                command.amount(),
                command.vendorType());

        PgConfirmResult result = pgGatewayPort.confirm(request);

        log.info("PgConfirmService: PG 호출 완료 orderId={} resultStatus={}",
                command.orderId(), result.status());
    }
}
