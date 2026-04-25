package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service PG 벤더 호출 + 재시도/DLQ/성공/실패 분기 서비스.
 * ADR-30: 재시도 = pg_outbox.available_at 지연 표현.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>PgConfirmPort.confirm() 호출.</li>
 *   <li>성공 → pg_outbox(payment.events.confirmed, APPROVED 포함) INSERT + pg_inbox APPROVED 전이.</li>
 *   <li>확정 실패(non-retryable) → pg_outbox(payment.events.confirmed, FAILED 포함) INSERT + pg_inbox FAILED 전이.</li>
 *   <li>재시도 가능 + attempt &lt; MAX(4) → pg_outbox(payment.commands.confirm, available_at=now+backoff) INSERT.</li>
 *   <li>재시도 가능 + attempt &gt;= MAX(4) → pg_outbox(payment.commands.confirm.dlq) INSERT (불변식 6).</li>
 * </ol>
 *
 * <p>TX commit 후 AFTER_COMMIT 이벤트 → PgOutboxChannel(T2a-05b/c 경로 재사용).
 * inbox IN_PROGRESS 상태는 재시도/DLQ 경로에서 유지 (QUARANTINED 전이는 T2b-02 DLQ consumer).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgVendorCallService {

    private static final SecureRandom RNG = new SecureRandom();

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final PgConfirmStrategySelector pgConfirmStrategySelector;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // -----------------------------------------------------------------------
    // 게이트웨이 결과 캡슐화 (try 블록 외부 변수 재할당 금지 대응)
    // F-12: enum + static class → sealed interface + record 패턴으로 통일
    // -----------------------------------------------------------------------

    private sealed interface GatewayOutcome
            permits GatewayOutcome.Success, GatewayOutcome.Retryable,
                    GatewayOutcome.NonRetryable, GatewayOutcome.HandledInternally {

        record Success(PgConfirmResult result) implements GatewayOutcome {}
        record Retryable(String message) implements GatewayOutcome {}
        record NonRetryable(String message) implements GatewayOutcome {}
        record HandledInternally(String message) implements GatewayOutcome {}
    }

    // -----------------------------------------------------------------------
    // 공개 API
    // -----------------------------------------------------------------------

    /**
     * 벤더 호출 + 재시도/DLQ/성공/실패 분기를 단일 TX 내에서 수행한다.
     *
     * @param request PG 확정 요청 DTO
     * @param attempt 현재 attempt 번호 (1부터 시작)
     * @param now     현재 시각
     */
    @Transactional
    public void callVendor(PgConfirmRequest request, int attempt, Instant now) {
        GatewayOutcome outcome = invokeConfirm(request);
        dispatchOutcome(outcome, request, attempt, now);
    }

    // -----------------------------------------------------------------------
    // 게이트웨이 호출 — 예외를 GatewayOutcome으로 변환
    // -----------------------------------------------------------------------

    private GatewayOutcome invokeConfirm(PgConfirmRequest request) {
        try {
            // K14: vendorType 기반 strategy 선택 — Toss/NicePay 동시 활성화 지원
            PgConfirmPort port = pgConfirmStrategySelector.select(request.vendorType());
            return new GatewayOutcome.Success(port.confirm(request));
        } catch (PgGatewayRetryableException e) {
            return new GatewayOutcome.Retryable(e.getMessage());
        } catch (PgGatewayNonRetryableException e) {
            return new GatewayOutcome.NonRetryable(e.getMessage());
        } catch (PgGatewayDuplicateHandledException e) {
            return new GatewayOutcome.HandledInternally(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 결과 분기
    // -----------------------------------------------------------------------

    private void dispatchOutcome(GatewayOutcome outcome, PgConfirmRequest request, int attempt, Instant now) {
        switch (outcome) {
            case GatewayOutcome.Success s -> handleSuccess(request.orderId(), s.result());
            case GatewayOutcome.Retryable ignored -> handleRetry(request, attempt, now);
            case GatewayOutcome.NonRetryable nr -> handleDefinitiveFailure(request.orderId(), nr.message());
            case GatewayOutcome.HandledInternally hi -> LogFmt.info(log, LogDomain.PG_VENDOR,
                    EventType.PG_VENDOR_DUPLICATE_HANDLED,
                    () -> "orderId=" + request.orderId() + " detail=" + hi.message());
        }
    }

    // -----------------------------------------------------------------------
    // 성공 처리
    // -----------------------------------------------------------------------

    private void handleSuccess(String orderId, PgConfirmResult result) {
        String payload = buildApprovedPayload(orderId, result);
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        pgInboxRepository.transitToApproved(orderId, payload);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS,
                () -> "orderId=" + orderId);
    }

    // -----------------------------------------------------------------------
    // 확정 실패 처리
    // -----------------------------------------------------------------------

    private void handleDefinitiveFailure(String orderId, String reasonCode) {
        String payload = buildFailedPayload(orderId, reasonCode);
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        pgInboxRepository.transitToFailed(orderId, payload, reasonCode);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DEFINITIVE_FAILURE,
                () -> "orderId=" + orderId + " reasonCode=" + reasonCode);
    }

    // -----------------------------------------------------------------------
    // 재시도 분기
    // -----------------------------------------------------------------------

    private void handleRetry(PgConfirmRequest request, int attempt, Instant now) {
        if (RetryPolicy.shouldRetry(attempt)) {
            insertRetryOutbox(request, attempt, now);
        } else {
            insertDlqOutbox(request, attempt);
        }
    }

    private void insertRetryOutbox(PgConfirmRequest request, int attempt, Instant now) {
        int nextAttempt = attempt + 1;
        Duration backoff = RetryPolicy.computeBackoff(nextAttempt, RNG);
        Instant availableAt = now.plus(backoff);
        String headersJson = buildAttemptHeader(nextAttempt);

        PgOutbox outbox = PgOutbox.createWithAvailableAt(
                null, PgTopics.COMMANDS_CONFIRM, request.orderId(),
                buildCommandPayload(request), headersJson, availableAt);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_RETRY_SCHEDULED,
                () -> "orderId=" + request.orderId() + " nextAttempt=" + nextAttempt + " availableAt=" + availableAt);
    }

    private void insertDlqOutbox(PgConfirmRequest request, int attempt) {
        String headersJson = buildAttemptHeader(attempt);
        PgOutbox outbox = PgOutbox.create(
                null, PgTopics.COMMANDS_CONFIRM_DLQ, request.orderId(),
                buildCommandPayload(request), headersJson);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DLQ,
                () -> "orderId=" + request.orderId() + " attempt=" + attempt);
    }

    // -----------------------------------------------------------------------
    // payload / header 빌더
    // -----------------------------------------------------------------------

    private String buildApprovedPayload(String orderId, PgConfirmResult result) {
        // eventUuid: payment-service ConfirmedEventConsumer 의 0단계 dedupe 키.
        // outbox row 1건당 1 uuid → relay 재시도 시 stored_status_result 재발행 경로에서도 동일 uuid 유지.
        String eventUuid = UUID.randomUUID().toString();
        // T-A1: 벤더 실측 amount/approvedAt 주입. approvedAtRaw 가 null 이면 Clock fallback.
        long amount = AmountConverter.fromBigDecimalStrict(result.amount());
        String approvedAtRaw = result.approvedAtRaw() != null
                ? result.approvedAtRaw()
                : OffsetDateTime.now(clock).toString();
        return payloadSerializer.serialize(
                ConfirmedEventPayload.approved(orderId, eventUuid, amount, approvedAtRaw));
    }

    private String buildFailedPayload(String orderId, String reasonCode) {
        String safeReason = reasonCode != null ? reasonCode : "";
        return payloadSerializer.serialize(
                ConfirmedEventPayload.failed(orderId, safeReason, UUID.randomUUID().toString())
        );
    }

    private String buildCommandPayload(PgConfirmRequest request) {
        // 재시도/DLQ 공통 스키마. PaymentConfirmConsumer/PaymentConfirmDlqConsumer는 동일 PgConfirmCommand를 기대한다.
        // eventUuid는 재컨슘 시 새 dedupe 키로 쓰이므로 outbox row마다 새로 발급.
        PgConfirmCommand command = new PgConfirmCommand(
                request.orderId(),
                request.paymentKey(),
                request.amount(),
                request.vendorType(),
                UUID.randomUUID().toString());
        try {
            return objectMapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "PgVendorCallService: PgConfirmCommand 직렬화 실패 orderId=" + request.orderId(), e);
        }
    }

    private String buildAttemptHeader(int attempt) {
        return "{\"attempt\":" + attempt + "}";
    }
}
