package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayConcurrentCallException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service PG 벤더 호출 서비스 — TX 경계 분리 버전.
 *
 * <p>invokeVendor (TX 없음) + applyOutcome (TX_B) 로 TX 경계를 분리한다.
 * 워커 VT 가 이 두 메서드를 순서대로 호출한다:
 * <ol>
 *   <li>{@link #invokeVendor} — TX 외부에서 벤더 HTTP 호출. VT 가 캐리어 양보, DB 자유 상태.</li>
 *   <li>{@link #applyOutcome} — @Transactional TX_B. 벤더 응답 5분기 처리 + RDB 반영.</li>
 * </ol>
 *
 * <p>응답 분기 (@Transactional TX_B):
 * <ol>
 *   <li>승인 성공 → APPROVED 종결 + Outbox INSERT</li>
 *   <li>확정 실패 → FAILED 종결 + Outbox INSERT</li>
 *   <li>일시 실패 잔여 시도 → 재시도 명령 INSERT (IN_PROGRESS 유지)</li>
 *   <li>일시 실패 시도 소진 → 격리 명령 INSERT (IN_PROGRESS 유지)</li>
 *   <li>ALREADY_PROCESSED → {@link DuplicateApprovalHandler} 위임 (보정 경로 진입)</li>
 *   <li>겹친 호출 거부(IDEMPOTENT_REQUEST_PROCESSING) → 원 호출이 결과를 낼 예정이므로
 *       시도횟수·재시도 명령·상태 전이를 건드리지 않고 로그와 지표만 남긴다</li>
 * </ol>
 *
 * <p>재시도는 pg_outbox.available_at 의 지연 시각으로 표현된다 — 별도 스케줄러 큐 없음.
 *
 */
@Slf4j
@Service
public class PgVendorCallService {

    /**
     * 같은 멱등키로 아직 처리 중인 원 호출과 겹쳐 벤더가 거부한 횟수.
     * 원 호출이 결과를 낼 예정이라 상태 전이를 건드리지 않기로 한 결정의 관측성 근거.
     */
    static final String CONCURRENT_CALL_COUNTER_NAME = "pg_vendor.concurrent_call_total";

    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final PgConfirmStrategySelector pgConfirmStrategySelector;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final DuplicateApprovalHandler duplicateApprovalHandler;
    private final SecureRandom secureRandom;
    private final Counter concurrentCallCounter;

    public PgVendorCallService(
            PgInboxRepository pgInboxRepository,
            PgOutboxRepository pgOutboxRepository,
            PgConfirmStrategySelector pgConfirmStrategySelector,
            ApplicationEventPublisher applicationEventPublisher,
            ConfirmedEventPayloadSerializer payloadSerializer,
            ObjectMapper objectMapper,
            Clock clock,
            DuplicateApprovalHandler duplicateApprovalHandler,
            SecureRandom secureRandom,
            MeterRegistry meterRegistry
    ) {
        this.pgInboxRepository = pgInboxRepository;
        this.pgOutboxRepository = pgOutboxRepository;
        this.pgConfirmStrategySelector = pgConfirmStrategySelector;
        this.applicationEventPublisher = applicationEventPublisher;
        this.payloadSerializer = payloadSerializer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.duplicateApprovalHandler = duplicateApprovalHandler;
        this.secureRandom = secureRandom;
        this.concurrentCallCounter = Counter.builder(CONCURRENT_CALL_COUNTER_NAME)
                .description("같은 멱등키로 아직 처리 중인 원 호출과 겹쳐 벤더가 거부한 횟수")
                .register(meterRegistry);
    }

    // -----------------------------------------------------------------------
    // 공개 API — TX 분리 버전
    // -----------------------------------------------------------------------

    /**
     * 벤더 HTTP 호출 전용 메서드 — TX 없음.
     *
     * <p>워커 VT 가 TX_A (PENDING→IN_PROGRESS) 커밋 후 이 메서드를 TX 외부에서 호출한다.
     * DB 커넥션을 점유하지 않은 상태로 벤더 HTTP 대기를 수행한다.
     *
     * @param request PG 확정 요청 DTO
     * @return 벤더 호출 결과 {@link GatewayOutcome}
     */
    public GatewayOutcome invokeVendor(PgConfirmRequest request) {
        return invokeConfirm(request);
    }

    /**
     * 벤더 응답 5분기 처리 — @Transactional TX_B.
     *
     * <p>invokeVendor 반환값을 받아 inbox 종결 또는 재시도/격리 명령 INSERT 를 TX 안에서 수행한다.
     *
     * @param outcome invokeVendor 결과
     * @param request PG 확정 요청 DTO
     * @param attempt 현재 attempt 번호 (1부터 시작)
     * @param now     현재 시각
     */
    @Transactional
    public void applyOutcome(GatewayOutcome outcome, PgConfirmRequest request, int attempt, Instant now) {
        dispatchOutcome(outcome, request, attempt, now);
    }

    // -----------------------------------------------------------------------
    // 게이트웨이 호출 — 예외를 GatewayOutcome으로 변환
    // -----------------------------------------------------------------------

    private GatewayOutcome invokeConfirm(PgConfirmRequest request) {
        try {
            // vendorType 기반 strategy 선택 — Toss/NicePay 동시 활성화 지원
            PgConfirmPort port = pgConfirmStrategySelector.select(request.vendorType());
            return new GatewayOutcome.Success(port.confirm(request));
        } catch (PgGatewayRetryableException e) {
            return new GatewayOutcome.Retryable(e.getMessage());
        } catch (PgGatewayNonRetryableException e) {
            return new GatewayOutcome.NonRetryable(e.getMessage());
        } catch (PgGatewayDuplicateHandledException e) {
            return new GatewayOutcome.HandledInternally(e.getMessage());
        } catch (PgGatewayConcurrentCallException e) {
            return new GatewayOutcome.ConcurrentCall(e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 결과 분기
    // -----------------------------------------------------------------------

    private void dispatchOutcome(GatewayOutcome outcome, PgConfirmRequest request, int attempt, Instant now) {
        switch (outcome) {
            case GatewayOutcome.Success s -> handleSuccess(request.orderId(), s.result());
            case GatewayOutcome.Retryable(String reason) -> handleRetry(request, attempt, now, reason);
            case GatewayOutcome.NonRetryable nr -> handleDefinitiveFailure(request.orderId(), nr.message());
            case GatewayOutcome.HandledInternally hi -> handleDuplicate(request, hi.message());
            case GatewayOutcome.ConcurrentCall cc -> handleConcurrentCall(request.orderId(), cc.message());
        }
    }

    // -----------------------------------------------------------------------
    // 성공 처리
    // -----------------------------------------------------------------------

    private void handleSuccess(String orderId, PgConfirmResult result) {
        String payload = buildApprovedPayload(orderId, result);
        // 전이를 발행 행 저장보다 먼저 수행한다 — 좀비 회수 경합 등으로 이미 종결된 기록에
        // 뒤늦게 도착한 승인 응답이 발행 행을 남기면, 폴링 안전망이 그 행을 그대로 집어
        // 발행해 억제가 무력화된다. 반영 행 수 0이면 발행 행 자체를 만들지 않는다.
        int transitioned = pgInboxRepository.transitToApproved(orderId, payload);
        if (transitioned == 0) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS_GUARD_BLOCKED,
                    () -> "orderId=" + orderId);
            return;
        }

        PgOutbox outbox = PgOutbox.create(PgTopics.EVENTS_CONFIRMED, orderId, payload, null, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_SUCCESS,
                () -> "orderId=" + orderId);
    }

    // -----------------------------------------------------------------------
    // 확정 실패 처리
    // -----------------------------------------------------------------------

    private void handleDefinitiveFailure(String orderId, String reasonCode) {
        String payload = buildFailedPayload(orderId, reasonCode);
        // handleSuccess 와 동일한 이유로 전이를 발행 행 저장보다 먼저 수행한다.
        int transitioned = pgInboxRepository.transitToFailed(orderId, payload, reasonCode);
        if (transitioned == 0) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DEFINITIVE_FAILURE_GUARD_BLOCKED,
                    () -> "orderId=" + orderId + " reasonCode=" + reasonCode);
            return;
        }

        PgOutbox outbox = PgOutbox.create(PgTopics.EVENTS_CONFIRMED, orderId, payload, null, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DEFINITIVE_FAILURE,
                () -> "orderId=" + orderId + " reasonCode=" + reasonCode);
    }

    // -----------------------------------------------------------------------
    // ALREADY_PROCESSED 처리 — DuplicateApprovalHandler 위임
    // -----------------------------------------------------------------------

    private void handleDuplicate(PgConfirmRequest request, String message) {
        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DUPLICATE_HANDLED,
                () -> "orderId=" + request.orderId() + " detail=" + message);
        duplicateApprovalHandler.handleDuplicateApproval(
                request.orderId(), request.amount(), request.vendorType());
    }

    // -----------------------------------------------------------------------
    // 겹침 거부 처리 — 원 호출이 결과를 낼 예정이므로 물러난다
    // -----------------------------------------------------------------------

    /**
     * 같은 멱등키로 아직 처리 중인 원 호출과 겹쳐 벤더가 거부한 경우 — 시도횟수·재시도 명령·
     * 상태 전이를 아무것도 건드리지 않고 로그와 지표만 남긴 채 반환한다. 원 호출이 결과를 낸다.
     */
    private void handleConcurrentCall(String orderId, String message) {
        concurrentCallCounter.increment();
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_CONCURRENT_CALL,
                () -> "orderId=" + orderId + " detail=" + message);
    }

    // -----------------------------------------------------------------------
    // 재시도 분기
    // -----------------------------------------------------------------------

    private void handleRetry(PgConfirmRequest request, int attempt, Instant now, String reason) {
        if (RetryPolicy.shouldRetry(attempt)) {
            insertRetryOutbox(request, attempt, now, reason);
        } else {
            insertDlqOutbox(request, attempt, reason);
        }
    }

    private void insertRetryOutbox(PgConfirmRequest request, int attempt, Instant now, String reason) {
        int nextAttempt = attempt + 1;
        // 시도횟수 SoT 증가를 재시도 outbox INSERT 보다 먼저 수행한다 — 같은 TX_B 안이라
        // 원자성은 유지되며, 반영 행 수(가드 발동 시 0)로 이미 종결된 주문에 stray 재시도
        // outbox INSERT + 발행 이벤트가 나가는 것을 막는다.
        int incremented = pgInboxRepository.incrementAttempt(request.orderId());
        if (incremented == 0) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_RETRY_GUARD_BLOCKED,
                    () -> "orderId=" + request.orderId()
                            + " nextAttempt=" + nextAttempt
                            + " reason=" + reason);
            return;
        }

        // 백오프는 실패한 attempt 기준으로 계산한다 — nextAttempt(증가 후 값)를 넘기면
        // 대기가 한 회차씩 밀려 첫 재시도부터 설계값(2s)보다 길어진다.
        Duration backoff = RetryPolicy.computeBackoff(attempt, secureRandom);
        Instant availableAt = now.plus(backoff);
        String headersJson = buildAttemptHeader(nextAttempt);

        PgOutbox outbox = PgOutbox.createWithAvailableAt(
                PgTopics.COMMANDS_CONFIRM, request.orderId(),
                buildCommandPayload(request), headersJson, availableAt, now);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_RETRY_SCHEDULED,
                () -> "orderId=" + request.orderId()
                        + " nextAttempt=" + nextAttempt
                        + " availableAt=" + availableAt
                        + " reason=" + reason);
    }

    private void insertDlqOutbox(PgConfirmRequest request, int attempt, String reason) {
        String headersJson = buildAttemptHeader(attempt);
        PgOutbox outbox = PgOutbox.create(
                PgTopics.COMMANDS_CONFIRM_DLQ, request.orderId(),
                buildCommandPayload(request), headersJson, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DLQ,
                () -> "orderId=" + request.orderId()
                        + " attempt=" + attempt
                        + " reason=" + reason);
    }

    // -----------------------------------------------------------------------
    // payload / header 빌더
    // -----------------------------------------------------------------------

    private String buildApprovedPayload(String orderId, PgConfirmResult result) {
        // eventUuid: payment-service ConfirmedEventConsumer 의 0단계 dedupe 키.
        // outbox row 1건당 1 uuid → relay 재시도 시 stored_status_result 재발행 경로에서도 동일 uuid 유지.
        String eventUuid = UUID.randomUUID().toString();
        // 벤더 실측 amount/approvedAt 주입 — approvedAtRaw 가 null 이면 Clock fallback 으로 보정한다.
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
