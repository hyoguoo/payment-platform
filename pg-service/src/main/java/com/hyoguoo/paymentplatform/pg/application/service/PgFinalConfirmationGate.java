package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayloadSerializer;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service Final Confirmation Gate (FCG).
 * ADR-15(FCG 불변): PG 내부 재시도 루프 소진 후 벤더 getStatus 1회 최종 확인.
 * 재시도 래핑 금지 — getStatusByOrderId() 단 1회 호출.
 *
 * <p>결과 분기:
 * <ul>
 *   <li>APPROVED → pg_outbox(payment.events.confirmed, APPROVED) INSERT + pg_inbox APPROVED 전이.</li>
 *   <li>FAILED(확정 실패) → pg_outbox(payment.events.confirmed, FAILED) INSERT + pg_inbox FAILED 전이.</li>
 *   <li>INDETERMINATE(timeout·5xx·네트워크 에러) → 무조건 QUARANTINED(FCG_INDETERMINATE) + pg_outbox INSERT
 *       (재시도 없음, FCG 불변).</li>
 * </ul>
 *
 * <p>payment-service는 FCG 존재를 모른다 (캡슐화).
 * 호출 주체는 후속 Phase에서 DLQ 전이 대신 FCG 선행 경로로 연결 예정 (T2b-04 이후).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgFinalConfirmationGate {

    private static final String REASON_FCG_INDETERMINATE = "FCG_INDETERMINATE";

    /**
     * 벤더 상태 조회 결과 중 APPROVED로 매핑되는 PgPaymentStatus 집합.
     * Toss 기준: DONE = 결제 완료 (APPROVED).
     */
    private static final Set<PgPaymentStatus> APPROVED_STATUSES = Set.of(PgPaymentStatus.DONE);

    /**
     * 벤더 상태 조회 결과 중 FAILED로 매핑되는 PgPaymentStatus 집합.
     * 확정 실패 상태: ABORTED, CANCELED, PARTIAL_CANCELED, EXPIRED.
     */
    private static final Set<PgPaymentStatus> FAILED_STATUSES = Set.of(
            PgPaymentStatus.ABORTED,
            PgPaymentStatus.CANCELED,
            PgPaymentStatus.PARTIAL_CANCELED,
            PgPaymentStatus.EXPIRED
    );

    private final PgStatusLookupPort pgStatusLookupPort;
    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;

    // -----------------------------------------------------------------------
    // FCG 3-way 결과 캡슐화 (try 블록 외부 변수 재할당 금지 대응)
    // -----------------------------------------------------------------------

    private enum FcgOutcomeKind { APPROVED, FAILED, INDETERMINATE }

    private static final class FcgOutcome {

        private final FcgOutcomeKind kind;
        private final String storedStatusResult;

        private FcgOutcome(FcgOutcomeKind kind, String storedStatusResult) {
            this.kind = kind;
            this.storedStatusResult = storedStatusResult;
        }

        static FcgOutcome approved(String statusResult) {
            return new FcgOutcome(FcgOutcomeKind.APPROVED, statusResult);
        }

        static FcgOutcome failed(String statusResult) {
            return new FcgOutcome(FcgOutcomeKind.FAILED, statusResult);
        }

        static FcgOutcome indeterminate() {
            return new FcgOutcome(FcgOutcomeKind.INDETERMINATE, null);
        }
    }

    // -----------------------------------------------------------------------
    // 공개 API
    // -----------------------------------------------------------------------

    /**
     * 재시도 소진 후 최종 상태 확인을 단일 TX 내에서 수행한다.
     * FCG 불변: getStatusByOrderId() 1회만 호출. 예외 → 재시도 없이 QUARANTINED.
     *
     * @param orderId   주문 ID
     * @param eventUuid 이벤트 UUID (향후 멱등성 키로 활용 예정)
     * @param amount    원화 금액
     */
    @Transactional
    public void performFinalCheck(String orderId, String eventUuid, long amount) {
        FcgOutcome outcome = queryStatusOnce(orderId);
        dispatchOutcome(outcome, orderId, amount);
    }

    // -----------------------------------------------------------------------
    // 벤더 상태 조회 — 1회만, 예외는 INDETERMINATE로 변환 (FCG 불변)
    // -----------------------------------------------------------------------

    private FcgOutcome queryStatusOnce(String orderId) {
        try {
            PgStatusResult statusResult = pgStatusLookupPort.getStatusByOrderId(orderId);
            return mapStatusResult(statusResult);
        } catch (PgGatewayRetryableException | PgGatewayNonRetryableException e) {
            log.warn("PgFinalConfirmationGate: 벤더 상태 조회 판정 불가 → INDETERMINATE orderId={} cause={}",
                    orderId, e.getMessage());
            return FcgOutcome.indeterminate();
        }
    }

    private FcgOutcome mapStatusResult(PgStatusResult statusResult) {
        PgPaymentStatus pgStatus = statusResult.status();
        if (APPROVED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return FcgOutcome.approved(storedResult);
        }
        if (FAILED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return FcgOutcome.failed(storedResult);
        }
        // READY, IN_PROGRESS, WAITING_FOR_DEPOSIT 등 미확정 상태 → INDETERMINATE 처리
        log.warn("PgFinalConfirmationGate: 미확정 상태 → INDETERMINATE orderId={} pgStatus={}",
                statusResult.orderId(), pgStatus);
        return FcgOutcome.indeterminate();
    }

    // -----------------------------------------------------------------------
    // 결과 분기
    // -----------------------------------------------------------------------

    private void dispatchOutcome(FcgOutcome outcome, String orderId, long amount) {
        switch (outcome.kind) {
            case APPROVED -> handleApproved(orderId, outcome.storedStatusResult);
            case FAILED -> handleFailed(orderId, outcome.storedStatusResult);
            case INDETERMINATE -> handleIndeterminate(orderId, amount);
        }
    }

    // -----------------------------------------------------------------------
    // APPROVED 처리
    // -----------------------------------------------------------------------

    private void handleApproved(String orderId, String storedStatusResult) {
        pgInboxRepository.transitToApproved(orderId, storedStatusResult);

        String payload = buildConfirmedPayload(orderId, "APPROVED", null);
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);

        log.info("PgFinalConfirmationGate: APPROVED 확정 orderId={} outboxId={}", orderId, saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // FAILED 처리
    // -----------------------------------------------------------------------

    private void handleFailed(String orderId, String storedStatusResult) {
        pgInboxRepository.transitToFailed(orderId, storedStatusResult, "FCG_CONFIRMED_FAILED");

        String payload = buildConfirmedPayload(orderId, "FAILED", "FCG_CONFIRMED_FAILED");
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);

        log.info("PgFinalConfirmationGate: FAILED 확정 orderId={} outboxId={}", orderId, saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // INDETERMINATE 처리 (timeout/5xx/network 에러 → 무조건 QUARANTINED)
    // -----------------------------------------------------------------------

    private void handleIndeterminate(String orderId, long amount) {
        pgInboxRepository.transitToQuarantined(orderId, REASON_FCG_INDETERMINATE);

        String payload = buildConfirmedPayload(orderId, "QUARANTINED", REASON_FCG_INDETERMINATE);
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);

        log.warn("PgFinalConfirmationGate: QUARANTINED(FCG_INDETERMINATE) 전이 orderId={} outboxId={}",
                orderId, saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // payload 빌더
    // -----------------------------------------------------------------------

    private String buildStatusJson(String orderId, String pgStatusName) {
        return "{\"orderId\":\"" + orderId + "\",\"pgStatus\":\"" + pgStatusName + "\"}";
    }

    private String buildConfirmedPayload(String orderId, String status, String reasonCode) {
        String eventUuid = UUID.randomUUID().toString();
        ConfirmedEventPayload payload = switch (status) {
            case "APPROVED" -> ConfirmedEventPayload.approved(orderId, eventUuid);
            case "FAILED" -> ConfirmedEventPayload.failed(orderId, reasonCode, eventUuid);
            case "QUARANTINED" -> ConfirmedEventPayload.quarantined(orderId, reasonCode, eventUuid);
            default -> throw new IllegalArgumentException("지원하지 않는 status: " + status);
        };
        return payloadSerializer.serialize(payload);
    }
}
