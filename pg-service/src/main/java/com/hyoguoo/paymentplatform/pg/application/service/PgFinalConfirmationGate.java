package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmStatus;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.core.common.metrics.PgDlqReachMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * pg-service Final Confirmation Gate (FCG).
 * PG 내부 재시도 루프가 소진된 뒤 벤더 getStatus 를 단 1회 호출해 최종 상태를 확정한다.
 * 재시도 래핑 금지 — getStatusByOrderId() 는 정확히 한 번만 호출되어야 한다.
 *
 * <p>판정 순서는 금액 대조가 먼저다 — 조회 응답 금액이 접수 금액과 다르면 상태가 승인이어도
 * AMOUNT_MISMATCH 로 격리하고, 금액이 맞을 때만 상태로 분기한다.
 *
 * <p>결과 분기:
 * <ul>
 *   <li>APPROVED → pg_outbox(payment.events.confirmed, APPROVED) INSERT + pg_inbox APPROVED 전이.</li>
 *   <li>FAILED(취소·중단·만료) → pg_outbox(payment.events.confirmed, FAILED) INSERT + pg_inbox FAILED 전이.</li>
 *   <li>QUARANTINED → pg_outbox INSERT + pg_inbox QUARANTINED 전이. 사유 네 갈래:
 *     {@code FCG_INDETERMINATE}(조회 자체가 실행 시 예외로 실패), {@code AMOUNT_MISMATCH}(조회 금액이
 *     접수 금액과 다름), {@code FCG_PARTIAL_CANCELED}(벤더가 부분 취소로 응답 — 완전 실패로 취급하지
 *     않는다), {@code FCG_VENDOR_UNSETTLED}(벤더가 아직 결론을 내지 않음, 입금 대기 등). 재시도 없음(FCG 불변).</li>
 * </ul>
 *
 * <p>조회 과정에서 나는 모든 실행 시 예외를 확인 불가로 접는다 — 게이트웨이 재시도 가능/불가
 * 예외만 잡으면 처리 기록 없는 주문에 벤더 전략이 던지는 다른 예외가 그대로 새어나간다.
 * 관리자 화면 벤더 조회 서비스와 같은 형태다.
 *
 * <p><b>TX 경계 분리</b> — {@link PgVendorCallService} 의 invokeVendor/applyOutcome 분리와 같은 이유다.
 * 벤더 조회는 HTTP 호출이라 DB 커넥션을 쥔 채 대기하면 안 된다. 호출자는 두 메서드를 순서대로 호출한다:
 * <ol>
 *   <li>{@link #invokeVendor} — TX 없음. 벤더 getStatus 1회 호출 후 결과를 3-way 결과로 반환.</li>
 *   <li>{@link #applyOutcome} — {@code @Transactional}. 반환값을 받아 pg_inbox 전이 + pg_outbox INSERT
 *       + 이벤트 발행을 TX 안에서 수행.</li>
 * </ol>
 *
 * <p>payment-service는 FCG 존재를 모른다 (캡슐화).
 * 호출 주체는 후속 Phase에서 DLQ 전이 대신 FCG 선행 경로로 연결 예정.
 */
@Slf4j
@Service
public class PgFinalConfirmationGate {

    private static final String REASON_FCG_INDETERMINATE = "FCG_INDETERMINATE";
    private static final String REASON_FCG_VENDOR_UNSETTLED = "FCG_VENDOR_UNSETTLED";
    private static final String REASON_FCG_PARTIAL_CANCELED = "FCG_PARTIAL_CANCELED";
    private static final String REASON_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    /**
     * 벤더 상태 조회 결과 중 APPROVED로 매핑되는 PgPaymentStatus 집합.
     * Toss 기준: DONE = 결제 완료 (APPROVED).
     */
    private static final Set<PgPaymentStatus> APPROVED_STATUSES = Set.of(PgPaymentStatus.DONE);

    /**
     * 벤더 상태 조회 결과 중 FAILED로 매핑되는 PgPaymentStatus 집합.
     * 확정 실패 상태: ABORTED, CANCELED, EXPIRED.
     * PARTIAL_CANCELED 는 이 집합에서 제외한다 — 부분 환불된 결제를 완전 실패로 접지 않고
     * 전용 사유({@code FCG_PARTIAL_CANCELED})로 격리해 사람 판단으로 넘긴다.
     */
    private static final Set<PgPaymentStatus> FAILED_STATUSES = Set.of(
            PgPaymentStatus.ABORTED,
            PgPaymentStatus.CANCELED,
            PgPaymentStatus.EXPIRED
    );

    /**
     * 관문 결과 분포 카운터. 태그 {@code outcome} 6종 — {@link #handleApproved}/{@link #handleFailed}/
     * {@link #handleQuarantined} 가 전이 반영을 확인한 뒤에만 증가시킨다(반영 0건 가드에 걸린 결과는
     * 아직 종결되지 않았으므로 세지 않는다). 소진 도달 알람이 이 카운터의 합산을 "소진이 발생했다"는
     * 신호로 삼는다 — 관문을 거친 결과는 하나도 빠짐없이 어느 태그로든 잡혀야 한다.
     */
    static final String OUTCOME_COUNTER_NAME = "pg_final_confirmation.outcome_total";

    private static final String TAG_OUTCOME = "outcome";
    private static final String OUTCOME_APPROVED = "approved";
    private static final String OUTCOME_FAILED = "failed";
    private static final String OUTCOME_INDETERMINATE = "indeterminate";
    private static final String OUTCOME_VENDOR_UNSETTLED = "vendor_unsettled";
    private static final String OUTCOME_PARTIAL_CANCELED = "partial_canceled";
    private static final String OUTCOME_AMOUNT_MISMATCH = "amount_mismatch";

    private final PgStatusLookupStrategySelector pgStatusLookupStrategySelector;
    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;
    private final Clock clock;
    private final Map<String, Counter> outcomeCounters;
    private final PgDlqReachMetrics pgDlqReachMetrics;

    public PgFinalConfirmationGate(
            PgStatusLookupStrategySelector pgStatusLookupStrategySelector,
            PgInboxRepository pgInboxRepository,
            PgOutboxRepository pgOutboxRepository,
            ApplicationEventPublisher applicationEventPublisher,
            ConfirmedEventPayloadSerializer payloadSerializer,
            Clock clock,
            MeterRegistry meterRegistry,
            PgDlqReachMetrics pgDlqReachMetrics
    ) {
        this.pgStatusLookupStrategySelector = pgStatusLookupStrategySelector;
        this.pgInboxRepository = pgInboxRepository;
        this.pgOutboxRepository = pgOutboxRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.payloadSerializer = payloadSerializer;
        this.clock = clock;
        this.outcomeCounters = buildOutcomeCounters(meterRegistry);
        this.pgDlqReachMetrics = pgDlqReachMetrics;
    }

    /**
     * outcome 태그 6종을 0으로 사전 등록한다 — 기동 직후 Prometheus 스크레이프에 "No data" 없이
     * 0 시리즈가 노출됨. 기존 지표 클래스({@link com.hyoguoo.paymentplatform.pg.core.common.metrics.PgDlqReachMetrics}
     * 등)의 eager 등록 방식을 따른다.
     */
    private static Map<String, Counter> buildOutcomeCounters(MeterRegistry meterRegistry) {
        List<String> outcomes = List.of(
                OUTCOME_APPROVED, OUTCOME_FAILED, OUTCOME_INDETERMINATE,
                OUTCOME_VENDOR_UNSETTLED, OUTCOME_PARTIAL_CANCELED, OUTCOME_AMOUNT_MISMATCH
        );
        Map<String, Counter> counters = new LinkedHashMap<>();
        for (String outcome : outcomes) {
            counters.put(outcome, Counter.builder(OUTCOME_COUNTER_NAME)
                    .description("Total number of PgFinalConfirmationGate outcomes by type")
                    .tag(TAG_OUTCOME, outcome)
                    .register(meterRegistry));
        }
        return Map.copyOf(counters);
    }

    private static String resolveQuarantineOutcomeTag(String reasonCode) {
        return switch (reasonCode) {
            case REASON_FCG_INDETERMINATE -> OUTCOME_INDETERMINATE;
            case REASON_FCG_VENDOR_UNSETTLED -> OUTCOME_VENDOR_UNSETTLED;
            case REASON_FCG_PARTIAL_CANCELED -> OUTCOME_PARTIAL_CANCELED;
            case REASON_AMOUNT_MISMATCH -> OUTCOME_AMOUNT_MISMATCH;
            default -> throw new IllegalStateException("알 수 없는 FCG 격리 사유: " + reasonCode);
        };
    }

    // -----------------------------------------------------------------------
    // FCG 3-way 결과 캡슐화 (try 블록 외부 변수 재할당 금지 대응) — sealed interface + record 패턴.
    // -----------------------------------------------------------------------

    sealed interface FcgOutcome
            permits FcgOutcome.Approved, FcgOutcome.Failed, FcgOutcome.Quarantined {

        record Approved(String storedStatusResult, String approvedAtRaw) implements FcgOutcome {}
        record Failed(String storedStatusResult) implements FcgOutcome {}
        record Quarantined(String reasonCode) implements FcgOutcome {}
    }

    // -----------------------------------------------------------------------
    // 공개 API — TX 분리 버전. 호출자가 두 메서드를 이 순서로 호출한다.
    // -----------------------------------------------------------------------

    /**
     * 벤더 상태 조회 전용 — TX 없음.
     * FCG 불변: getStatusByOrderId() 1회만 호출. 예외 → 재시도 없이 QUARANTINED 결과로 흡수.
     *
     * @param orderId    주문 ID
     * @param eventUuid  이벤트 UUID (향후 멱등성 키로 활용 예정)
     * @param amount     원화 금액
     * @param vendorType PG 벤더 구분 — PgStatusLookupStrategySelector 분기에 사용
     * @return 벤더 조회 결과를 담은 3-way 결과 ({@link #applyOutcome} 에 그대로 전달)
     */
    public FcgOutcome invokeVendor(String orderId, String eventUuid, long amount, PgVendorType vendorType) {
        return queryStatusOnce(orderId, amount, vendorType);
    }

    /**
     * 조회 결과 반영 — {@code @Transactional}.
     * {@link #invokeVendor} 반환값을 받아 pg_inbox 전이 + pg_outbox INSERT + 이벤트 발행을 TX 안에서 수행한다.
     *
     * @param outcome invokeVendor 반환값
     * @param orderId 주문 ID
     * @param amount  원화 금액
     */
    @Transactional
    public void applyOutcome(FcgOutcome outcome, String orderId, long amount) {
        dispatchOutcome(outcome, orderId, amount);
    }

    // -----------------------------------------------------------------------
    // 벤더 상태 조회 — 1회만, 조회 과정의 실행 시 예외 전체를 FCG_INDETERMINATE로 변환 (FCG 불변)
    // -----------------------------------------------------------------------

    private FcgOutcome queryStatusOnce(String orderId, long amount, PgVendorType vendorType) {
        try {
            // vendorType 기반 전략 선택 — Toss/NicePay 동시 활성 지원
            PgStatusLookupPort port = pgStatusLookupStrategySelector.select(vendorType);
            PgStatusResult statusResult = port.getStatusByOrderId(orderId);
            return mapStatusResult(statusResult, amount);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_INDETERMINATE,
                    () -> "orderId=" + orderId + " vendorType=" + vendorType
                            + " exceptionType=" + e.getClass().getSimpleName() + " cause=" + e.getMessage());
            return new FcgOutcome.Quarantined(REASON_FCG_INDETERMINATE);
        }
    }

    private FcgOutcome mapStatusResult(PgStatusResult statusResult, long amount) {
        if (isAmountMismatch(statusResult, amount)) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_AMOUNT_MISMATCH,
                    () -> "orderId=" + statusResult.orderId() + " expectedAmount=" + amount
                            + " vendorAmount=" + statusResult.amount());
            return new FcgOutcome.Quarantined(REASON_AMOUNT_MISMATCH);
        }

        PgPaymentStatus pgStatus = statusResult.status();
        if (APPROVED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return new FcgOutcome.Approved(storedResult, statusResult.approvedAtRaw());
        }
        if (FAILED_STATUSES.contains(pgStatus)) {
            String storedResult = buildStatusJson(statusResult.orderId(), pgStatus.name());
            return new FcgOutcome.Failed(storedResult);
        }
        if (pgStatus == PgPaymentStatus.PARTIAL_CANCELED) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_PARTIAL_CANCELED,
                    () -> "orderId=" + statusResult.orderId());
            return new FcgOutcome.Quarantined(REASON_FCG_PARTIAL_CANCELED);
        }
        // READY, IN_PROGRESS, WAITING_FOR_DEPOSIT 등 미확정 상태 → 벤더 미결론 사유로 격리
        LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_AMBIGUOUS_STATUS,
                () -> "orderId=" + statusResult.orderId() + " pgStatus=" + pgStatus);
        return new FcgOutcome.Quarantined(REASON_FCG_VENDOR_UNSETTLED);
    }

    private boolean isAmountMismatch(PgStatusResult statusResult, long amount) {
        return AmountConverter.fromBigDecimalStrict(statusResult.amount()) != amount;
    }

    // -----------------------------------------------------------------------
    // 결과 분기
    // -----------------------------------------------------------------------

    private void dispatchOutcome(FcgOutcome outcome, String orderId, long amount) {
        switch (outcome) {
            case FcgOutcome.Approved a -> handleApproved(orderId, a.storedStatusResult(), amount, a.approvedAtRaw());
            case FcgOutcome.Failed f -> handleFailed(orderId, f.storedStatusResult());
            case FcgOutcome.Quarantined q -> handleQuarantined(orderId, q.reasonCode());
        }
    }

    // -----------------------------------------------------------------------
    // APPROVED 처리
    // -----------------------------------------------------------------------

    private void handleApproved(String orderId, String storedStatusResult, long amount, String approvedAtRaw) {
        // 전이를 발행 행 저장보다 먼저 수행한다 — 경합으로 이미 종결된 기록에 뒤늦게 반영이
        // 시도되면 반영 행 수가 0이 되고, 이때 발행 행 자체를 만들지 않는다.
        int transitioned = pgInboxRepository.transitToApproved(orderId, storedStatusResult);
        if (transitioned == 0) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_APPROVED_GUARD_BLOCKED,
                    () -> "orderId=" + orderId);
            return;
        }
        outcomeCounters.get(OUTCOME_APPROVED).increment();

        // 조회 응답의 승인 시각 원문을 그대로 싣는다. 원문이 없을 때만 Clock 기반 시각으로 대체한다.
        String resolvedApprovedAtRaw = approvedAtRaw != null ? approvedAtRaw : OffsetDateTime.now(clock).toString();
        String payload = buildApprovedPayload(orderId, amount, resolvedApprovedAtRaw);
        PgOutbox outbox = PgOutbox.create(PgTopics.EVENTS_CONFIRMED, orderId, payload, null, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);

        LogFmt.info(log, LogDomain.PG, EventType.PG_FCG_APPROVED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // FAILED 처리
    // -----------------------------------------------------------------------

    private void handleFailed(String orderId, String storedStatusResult) {
        // handleApproved 와 동일한 이유로 전이를 발행 행 저장보다 먼저 수행한다.
        int transitioned = pgInboxRepository.transitToFailed(orderId, storedStatusResult, "FCG_CONFIRMED_FAILED");
        if (transitioned == 0) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_FAILED_GUARD_BLOCKED,
                    () -> "orderId=" + orderId);
            return;
        }
        outcomeCounters.get(OUTCOME_FAILED).increment();

        String payload = buildConfirmedPayload(orderId, ConfirmStatus.FAILED, "FCG_CONFIRMED_FAILED");
        PgOutbox outbox = PgOutbox.create(PgTopics.EVENTS_CONFIRMED, orderId, payload, null, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);

        LogFmt.info(log, LogDomain.PG, EventType.PG_FCG_FAILED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId());
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
    }

    // -----------------------------------------------------------------------
    // QUARANTINED 처리 (조회 실패·금액 불일치·부분 취소·벤더 미결론 네 사유 공통)
    // -----------------------------------------------------------------------

    private void handleQuarantined(String orderId, String reasonCode) {
        // handleApproved 와 동일한 이유로 전이를 발행 행 저장보다 먼저 수행한다.
        boolean transitioned = pgInboxRepository.transitToQuarantined(orderId, reasonCode);
        if (!transitioned) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_QUARANTINED_GUARD_BLOCKED,
                    () -> "orderId=" + orderId + " reasonCode=" + reasonCode);
            return;
        }
        outcomeCounters.get(resolveQuarantineOutcomeTag(reasonCode)).increment();
        pgDlqReachMetrics.record();

        String payload = buildConfirmedPayload(orderId, ConfirmStatus.QUARANTINED, reasonCode);
        PgOutbox outbox = PgOutbox.create(PgTopics.EVENTS_CONFIRMED, orderId, payload, null, clock.instant());
        PgOutbox saved = pgOutboxRepository.save(outbox);

        LogFmt.warn(log, LogDomain.PG, EventType.PG_FCG_QUARANTINED,
                () -> "orderId=" + orderId + " outboxId=" + saved.getId() + " reasonCode=" + reasonCode);
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

    private String buildConfirmedPayload(String orderId, ConfirmStatus status, String reasonCode) {
        String eventUuid = UUID.randomUUID().toString();
        ConfirmedEventPayload payload = switch (status) {
            case FAILED -> ConfirmedEventPayload.failed(orderId, reasonCode, eventUuid);
            case QUARANTINED -> ConfirmedEventPayload.quarantined(orderId, reasonCode, eventUuid);
            default -> throw new IllegalArgumentException("지원하지 않는 status: " + status);
        };
        return payloadSerializer.serialize(payload);
    }
}
