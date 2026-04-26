package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 중복 승인 응답 방어 핸들러.
 * ADR-05 보강, ADR-21(캡슐화 대상): pg-service 내부 중복 승인 방어.
 *
 * <p>경로 (1) pg DB 레코드 존재:
 * <ul>
 *   <li>inbox.amount == vendor.amount → pg_outbox INSERT(stored_status_result 재발행) + publishEvent</li>
 *   <li>inbox.amount != vendor.amount → QUARANTINED+AMOUNT_MISMATCH + pg_outbox INSERT + publishEvent</li>
 * </ul>
 *
 * <p>경로 (2) pg DB 레코드 부재(ADR-05 보강 6번):
 * <ul>
 *   <li>vendor.amount == payloadAmount → inbox 신설(APPROVED) + 운영 알림(로그) + pg_outbox INSERT + publishEvent</li>
 *   <li>vendor.amount != payloadAmount → inbox 신설(QUARANTINED+AMOUNT_MISMATCH) + pg_outbox INSERT + publishEvent</li>
 * </ul>
 *
 * <p>vendor 호출 실패:
 * <ul>
 *   <li>timeout/5xx/네트워크 → QUARANTINED(VENDOR_INDETERMINATE) + pg_outbox INSERT + publishEvent</li>
 * </ul>
 *
 * <p>payment-service는 이 로직의 존재를 모른다(ADR-21(v) 불변).
 */
@Slf4j
@Service
public class DuplicateApprovalHandler {

    private static final String REASON_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    private static final String REASON_VENDOR_INDETERMINATE = "VENDOR_INDETERMINATE";

    /**
     * 벤더 상태 조회 결과 중 APPROVED로 매핑되는 PgPaymentStatus 집합.
     */
    private static final Set<PgPaymentStatus> APPROVED_STATUSES = Set.of(PgPaymentStatus.DONE);

    private final PgStatusLookupStrategySelector pgStatusLookupStrategySelector;
    private final PgInboxRepository pgInboxRepository;
    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConfirmedEventPayloadSerializer payloadSerializer;
    private final Clock clock;

    /**
     * 직접 PgStatusLookupPort 를 의존하지 않고 PgStatusLookupStrategySelector 를 거친다 —
     * vendorType 기반 strategy 선택으로 Toss/NicePay 동시 활성을 지원하며 cycle 도 해소된다.
     * Clock 은 APPROVED approvedAt fallback 에 사용한다.
     */
    public DuplicateApprovalHandler(
            PgStatusLookupStrategySelector pgStatusLookupStrategySelector,
            PgInboxRepository pgInboxRepository,
            PgOutboxRepository pgOutboxRepository,
            ApplicationEventPublisher applicationEventPublisher,
            ConfirmedEventPayloadSerializer payloadSerializer,
            Clock clock
    ) {
        this.pgStatusLookupStrategySelector = pgStatusLookupStrategySelector;
        this.pgInboxRepository = pgInboxRepository;
        this.pgOutboxRepository = pgOutboxRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.payloadSerializer = payloadSerializer;
        this.clock = clock;
    }

    // -----------------------------------------------------------------------
    // vendor 조회 결과 캡슐화 (try 블록 외부 변수 재할당 금지 대응) — sealed interface + record 패턴.
    // -----------------------------------------------------------------------

    private sealed interface VendorQueryOutcome
            permits VendorQueryOutcome.Success, VendorQueryOutcome.Indeterminate {

        record Success(PgStatusResult statusResult) implements VendorQueryOutcome {}
        record Indeterminate() implements VendorQueryOutcome {}
    }

    // -----------------------------------------------------------------------
    // 공개 API
    // -----------------------------------------------------------------------

    /**
     * DuplicateApprovalDetectedEvent 수신 리스너 — 벤더 전략이 발행한 이벤트를 받아 핸들러에 위임한다.
     *
     * <p>cycle 단절 의도: TossPaymentGatewayStrategy / NicepayPaymentGatewayStrategy 가
     * DuplicateApprovalHandler 를 직접 호출하면 PgStatusLookupPort 와 양방향 의존이 만들어진다.
     * 전략은 이벤트만 발행하고, 이 메서드가 {@code @EventListener} 로 수신해
     * {@link #handleDuplicateApproval} 에 위임함으로써 cycle 을 끊는다.
     *
     * <p>Spring ApplicationEvent 는 동기 처리이므로 기존 트랜잭션 컨텍스트 안에서 실행됨.
     * {@link #handleDuplicateApproval} 의 @Transactional(REQUIRED) 이 참여(join)한다.
     *
     * @param event DuplicateApprovalDetectedEvent (orderId, amount, paymentKey, reasonCode)
     */
    @EventListener
    public void onDuplicateApprovalDetected(DuplicateApprovalDetectedEvent event) {
        LogFmt.info(log, LogDomain.PG, EventType.PG_DUPLICATE_EVENT_RECEIVED,
                () -> "orderId=" + event.orderId() + " reasonCode=" + event.reasonCode()
                        + " vendorType=" + event.vendorType());
        handleDuplicateApproval(event.orderId(), event.amount(), event.vendorType());
    }

    /**
     * 중복 승인 응답 처리 — 2자 금액 대조 + pg DB 부재 경로 방어.
     *
     * @param orderId       주문 ID
     * @param payloadAmount command payload 금액 (scale=0, 양수)
     * @param vendorType    PG 벤더 구분 — PgStatusLookupStrategySelector 분기에 사용
     */
    @Transactional
    public void handleDuplicateApproval(String orderId, BigDecimal payloadAmount, PgVendorType vendorType) {
        long payloadAmountLong = AmountConverter.fromBigDecimalStrict(payloadAmount);

        // 1단계: vendor 상태 조회 — vendorType 기반 strategy 선택. 1회만 호출하고 실패 시 INDETERMINATE 처리.
        VendorQueryOutcome queryOutcome = queryVendorStatus(orderId, vendorType);

        if (queryOutcome instanceof VendorQueryOutcome.Indeterminate) {
            handleVendorIndeterminate(orderId, payloadAmountLong);
            return;
        }

        PgStatusResult vendorStatus = ((VendorQueryOutcome.Success) queryOutcome).statusResult();
        long vendorAmountLong = AmountConverter.fromBigDecimalStrict(vendorStatus.amount());

        // 2단계: pg DB 존재 여부 분기
        Optional<PgInbox> existingInbox = pgInboxRepository.findByOrderId(orderId);

        if (existingInbox.isPresent()) {
            handleDbExists(orderId, existingInbox.get(), vendorAmountLong, vendorStatus);
        } else {
            handleDbAbsent(orderId, payloadAmountLong, vendorAmountLong);
        }
    }

    // -----------------------------------------------------------------------
    // vendor 조회 — 1회만, 예외 시 INDETERMINATE 변환
    // -----------------------------------------------------------------------

    private VendorQueryOutcome queryVendorStatus(String orderId, PgVendorType vendorType) {
        try {
            // vendorType 기반 전략 선택 — Toss/NicePay 동시 활성 지원
            PgStatusLookupPort port = pgStatusLookupStrategySelector.select(vendorType);
            PgStatusResult result = port.getStatusByOrderId(orderId);
            return new VendorQueryOutcome.Success(result);
        } catch (PgGatewayRetryableException | PgGatewayNonRetryableException e) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_VENDOR_INDETERMINATE,
                    () -> "orderId=" + orderId + " vendorType=" + vendorType + " cause=" + e.getMessage());
            return new VendorQueryOutcome.Indeterminate();
        }
    }

    // -----------------------------------------------------------------------
    // 경로 (1): pg DB 레코드 존재
    // -----------------------------------------------------------------------

    private void handleDbExists(String orderId, PgInbox inbox, long vendorAmountLong, PgStatusResult vendorStatus) {
        Long inboxAmount = inbox.getAmount();

        if (inboxAmount == null || inboxAmount.longValue() != vendorAmountLong) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_AMOUNT_MISMATCH_DB_EXISTS,
                    () -> "orderId=" + orderId + " inboxAmount=" + inboxAmount + " vendorAmount=" + vendorAmountLong);
            handleAmountMismatchDbExists(orderId);
            return;
        }

        // amount 일치 → stored_status_result 기반 재발행
        LogFmt.info(log, LogDomain.PG, EventType.PG_DUPLICATE_REEMIT,
                () -> "orderId=" + orderId);
        reemitStoredStatus(orderId, inbox);
    }

    private void reemitStoredStatus(String orderId, PgInbox inbox) {
        // pg_inbox 상태 변경 없음(이미 terminal)
        long outboxId = enqueueOutbox(orderId, inbox.getStoredStatusResult());

        LogFmt.info(log, LogDomain.PG, EventType.PG_DUPLICATE_REEMIT_DONE,
                () -> "orderId=" + orderId + " outboxId=" + outboxId);
    }

    private void handleAmountMismatchDbExists(String orderId) {
        // inbox.amount != vendor.amount → QUARANTINED+AMOUNT_MISMATCH (불변식 4c)
        pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);

        long outboxId = enqueueOutbox(orderId, buildConfirmedPayload(orderId, "QUARANTINED", REASON_AMOUNT_MISMATCH));

        LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_AMOUNT_MISMATCH_QUARANTINED_DB_EXISTS,
                () -> "orderId=" + orderId + " outboxId=" + outboxId);
    }

    // -----------------------------------------------------------------------
    // 경로 (2): pg DB 레코드 부재
    // -----------------------------------------------------------------------

    private void handleDbAbsent(String orderId, long payloadAmountLong, long vendorAmountLong) {
        if (payloadAmountLong == vendorAmountLong) {
            handleDbAbsentAmountMatch(orderId, payloadAmountLong);
        } else {
            handleDbAbsentAmountMismatch(orderId, payloadAmountLong, vendorAmountLong);
        }
    }

    private void handleDbAbsentAmountMatch(String orderId, long amountLong) {
        // vendor.amount == payloadAmount → inbox 신설(APPROVED) + 운영 알림
        LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_DB_ABSENT_APPROVED,
                () -> "orderId=" + orderId + " amount=" + amountLong);

        // inbox 신설(NONE→IN_PROGRESS→APPROVED)
        pgInboxRepository.transitNoneToInProgress(orderId, amountLong);
        // buildApprovedPayload 가 amount + approvedAt(Clock fallback) 을 포함한 payload 를 만든다.
        String approvedPayload = buildApprovedPayload(orderId, amountLong);
        pgInboxRepository.transitToApproved(orderId, approvedPayload);

        long outboxId = enqueueOutbox(orderId, approvedPayload);

        LogFmt.info(log, LogDomain.PG, EventType.PG_DUPLICATE_DB_ABSENT_APPROVED_DONE,
                () -> "orderId=" + orderId + " outboxId=" + outboxId);
    }

    private void handleDbAbsentAmountMismatch(String orderId, long payloadAmountLong, long vendorAmountLong) {
        // vendor.amount != payloadAmount → inbox 신설(QUARANTINED+AMOUNT_MISMATCH) (불변식 4c)
        LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_AMOUNT_MISMATCH_QUARANTINED_DB_ABSENT,
                () -> "orderId=" + orderId + " payloadAmount=" + payloadAmountLong + " vendorAmount=" + vendorAmountLong);

        // inbox 신설 후 QUARANTINED 전이 (amount=payloadAmount 기록)
        pgInboxRepository.transitNoneToInProgress(orderId, payloadAmountLong);
        pgInboxRepository.transitToQuarantined(orderId, REASON_AMOUNT_MISMATCH);

        long outboxId = enqueueOutbox(orderId, buildConfirmedPayload(orderId, "QUARANTINED", REASON_AMOUNT_MISMATCH));

        LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_AMOUNT_MISMATCH_QUARANTINED_DB_ABSENT,
                () -> "orderId=" + orderId + " outboxId=" + outboxId);
    }

    // -----------------------------------------------------------------------
    // vendor 조회 실패 경로
    // -----------------------------------------------------------------------

    private void handleVendorIndeterminate(String orderId, long payloadAmountLong) {
        // inbox가 없을 경우 생성 후 QUARANTINED
        Optional<PgInbox> existing = pgInboxRepository.findByOrderId(orderId);
        if (existing.isEmpty()) {
            pgInboxRepository.transitNoneToInProgress(orderId, payloadAmountLong);
        }
        pgInboxRepository.transitToQuarantined(orderId, REASON_VENDOR_INDETERMINATE);

        long outboxId = enqueueOutbox(orderId, buildConfirmedPayload(orderId, "QUARANTINED", REASON_VENDOR_INDETERMINATE));

        LogFmt.warn(log, LogDomain.PG, EventType.PG_DUPLICATE_QUARANTINED_VENDOR_INDETERMINATE,
                () -> "orderId=" + orderId + " outboxId=" + outboxId);
    }

    /**
     * pg_outbox row 저장 + PgOutboxReadyEvent 발행을 하나의 단위로 묶는다.
     * 5개 분기 경로에서 동일한 3단(create/save/publish) 을 공유한다.
     *
     * @return 저장된 pg_outbox row id (AFTER_COMMIT 이벤트 핸들러가 상관 키로 사용)
     */
    private long enqueueOutbox(String orderId, String payload) {
        PgOutbox outbox = PgOutbox.create(null, PgTopics.EVENTS_CONFIRMED, orderId, payload, null);
        PgOutbox saved = pgOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));
        return saved.getId();
    }

    // -----------------------------------------------------------------------
    // payload 빌더
    // -----------------------------------------------------------------------

    /**
     * APPROVED 확정 payload 빌드.
     * amount + approvedAt(Clock fallback) 을 함께 실어 payment-service 의 amount mismatch 역방향 방어선이 작동하게 한다.
     * DB absent 경로에서는 vendor status 조회 결과의 amount 를 그대로 사용하며,
     * approvedAt raw 문자열은 PgStatusResult 에 없으므로 Clock fallback 으로 현재 UTC 시각을 주입한다.
     */
    private String buildApprovedPayload(String orderId, long amount) {
        String approvedAtRaw = OffsetDateTime.now(clock).toString();
        return payloadSerializer.serialize(
                ConfirmedEventPayload.approved(orderId, UUID.randomUUID().toString(), amount, approvedAtRaw)
        );
    }

    private String buildConfirmedPayload(String orderId, String status, String reasonCode) {
        String eventUuid = UUID.randomUUID().toString();
        // APPROVED 분기는 buildApprovedPayload + enqueueOutbox(storedStatusResult) 경로로만 발행한다.
        // buildConfirmedPayload 호출처는 QUARANTINED/FAILED 만 전달한다.
        ConfirmedEventPayload payload = switch (status) {
            case "QUARANTINED" -> ConfirmedEventPayload.quarantined(orderId, reasonCode, eventUuid);
            case "FAILED" -> ConfirmedEventPayload.failed(orderId, reasonCode, eventUuid);
            default -> throw new IllegalArgumentException("지원하지 않는 status: " + status);
        };
        return payloadSerializer.serialize(payload);
    }
}
