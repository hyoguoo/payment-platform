package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service Final Confirmation Gate (FCG).
 * PG 내부 재시도 루프가 소진된 뒤 벤더 getStatus 를 단 1회 호출해 최종 상태를 확정한다.
 * 재시도 래핑 금지 — getStatusByOrderId() 는 정확히 한 번만 호출되어야 한다.
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
 * 호출 주체는 후속 Phase에서 DLQ 전이 대신 FCG 선행 경로로 연결 예정.
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

    private final PgStatusLookupStrategySelector pgStatusLookupStrategySelector;
    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;
    private final Clock clock;

    // -----------------------------------------------------------------------
    // FCG 3-way 결과 캡슐화 (try 블록 외부 변수 재할당 금지 대응) — sealed interface + record 패턴.
    // -----------------------------------------------------------------------

    private sealed interface FcgOutcome
            permits FcgOutcome.Approved, FcgOutcome.Failed, FcgOutcome.Indeterminate {

        record Approved(String storedStatusResult) implements FcgOutcome {}
        record Failed(String storedStatusResult) implements FcgOutcome {}
        record Indeterminate() implements FcgOutcome {}
    }

    // -----------------------------------------------------------------------
    // 공개 API
    // -----------------------------------------------------------------------

    /**
     * 재시도 소진 후 최종 상태 확인을 단일 TX 내에서 수행한다.
     * FCG 불변: getStatusByOrderId() 1회만 호출. 예외 → 재시도 없이 QUARANTINED.
     *
     * @param orderId    주문 ID
     * @param eventUuid  이벤트 UUID (향후 멱등성 키로 활용 예정)
     * @param amount     원화 금액
     * @param vendorType PG 벤더 구분 — PgStatusLookupStrategySelector 분기에 사용
     */
    @Transactional
    public void performFinalCheck(String orderId, String eventUuid, long amount, PgVendorType vendorType) {
        FcgOutcome outcome = queryStatusOnce(orderId, vendorType);
        dispatchOutcome(outcome, orderId, amount);
    }

    // -----------------------------------------------------------------------
    // 벤더 상태 조회 — 1회만, 예외는 INDETERMINATE로 변환 (FCG 불변)
    // -----------------------------------------------------------------------

    private FcgOutcome queryStatusOnce(String orderId, PgVendorType vendorType) {
        try {
            // vendorType 기반 전략 선택 — Toss/NicePay 동시 활성 지원
            PgStatusLookupPort port = pgStatusLookupStrategySelector.select(vendorType);
            PgStatusResult statusResult = port.getStatusByOrderId(orderId);
            return mapStatusResult(statusResult);
        } catch (PgGatewayRetryableException | PgGatewayNonRetryableException e) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_INDETERMINATE,
                    () -> "orderId=" + orderId + " vendorType=" + vendorType + " cause=" + e.getMessage());
            return new FcgOutcome.Indeterminate();
        }
    }

    private FcgOutcome mapStatusResult(PgStatusResult statusResult) {
        PgPaymentStatus pgStatus = statusResult.status();
        if (APPROVED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return new FcgOutcome.Approved(storedResult);
        }
        if (FAILED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return new FcgOutcome.Failed(storedResult);
        }
        // READY, IN_PROGRESS, WAITING_FOR_DEPOSIT 등 미확정 상태 → INDETERMINATE 처리
        LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_AMBIGUOUS_STATUS,
                () -> "orderId=" + statusResult.orderId() + " pgStatus=" + pgStatus);
        return new FcgOutcome.Indeterminate();
    }

    // -----------------------------------------------------------------------
    // 결과 분기
    // -----------------------------------------------------------------------

    private void dispatchOutcome(FcgOutcome outcome, String orderId, long amount) {
        switch (outcome) {
            case FcgOutcome.Approved a -> handleApproved(orderId, a.storedStatusResult(), amount);
            case FcgOutcome.Failed f -> handleFailed(orderId, f.storedStatusResult());
            case FcgOutcome.Indeterminate ignored -> handleIndeterminate(orderId, amount);
        }
    }

    // -----------------------------------------------------------------------
    // APPROVED 처리
    // -----------------------------------------------------------------------

    private void handleApproved(String orderId, String storedStatusResult, long amount) {
        pgInboxRepository.transitToApproved(orderId, storedStatusResult);

        // FCG 경로는 PgStatusResult 에 raw approvedAt 문자열이 없으므로 Clock 기반 UTC 시각을 fallback 으로 사용한다.
        String approvedAtRaw = OffsetDateTime.now(clock).toString();
        String payload = buildApprovedPayload(orderId, amount, approvedAtRaw);
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);

        LogFmt.info(log, LogDomain.PG, EventType.PG_FCG_APPROVED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId());
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

        LogFmt.info(log, LogDomain.PG, EventType.PG_FCG_FAILED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId());
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

        LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_QUARANTINED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // payload 빌더
    // -----------------------------------------------------------------------

    private String buildStatusJson(String orderId, String pgStatusName) {
        return "{\"orderId\":\"" + orderId + "\",\"pgStatus\":\"" + pgStatusName + "\"}";
    }

    /**
     * APPROVED payload 빌드 — amount 와 approvedAt 을 함께 실어 payment-service 의 amount mismatch 역방향 방어선이 작동하게 한다.
     */
    private String buildApprovedPayload(String orderId, long amount, String approvedAtRaw) {
        String eventUuid = UUID.randomUUID().toString();
        return payloadSerializer.serialize(
                ConfirmedEventPayload.approved(orderId, eventUuid, amount, approvedAtRaw));
    }

    private String buildConfirmedPayload(String orderId, String status, String reasonCode) {
        String eventUuid = UUID.randomUUID().toString();
        ConfirmedEventPayload payload = switch (status) {
            case "FAILED" -> ConfirmedEventPayload.failed(orderId, reasonCode, eventUuid);
            case "QUARANTINED" -> ConfirmedEventPayload.quarantined(orderId, reasonCode, eventUuid);
            default -> throw new IllegalArgumentException("지원하지 않는 status: " + status);
        };
        return payloadSerializer.serialize(payload);
    }
}
