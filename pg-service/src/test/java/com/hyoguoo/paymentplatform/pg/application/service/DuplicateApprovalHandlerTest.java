package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DuplicateApprovalHandler 단위 테스트.
 * 중복 승인 응답에 대한 2자 금액 대조 방어 — pg-service 내부 캡슐화 (payment-service 미노출).
 *
 * <p>경로 (1) pg DB 레코드 존재:
 *   - inbox.amount == vendor.amount → pg_outbox INSERT(stored_status_result 재발행) + publishEvent
 *   - inbox.amount != vendor.amount → QUARANTINED+AMOUNT_MISMATCH + pg_outbox INSERT + publishEvent
 *
 * <p>경로 (2) pg DB 레코드 부재:
 *   - vendor.amount == payloadAmount → inbox 신설(APPROVED) + 운영 알림 + pg_outbox INSERT + publishEvent
 *   - vendor.amount != payloadAmount → inbox 신설(QUARANTINED+AMOUNT_MISMATCH) + pg_outbox INSERT + publishEvent
 *
 * <p>경로 (3) vendor 조회 실패:
 *   - QUARANTINED(VENDOR_INDETERMINATE) + pg_outbox INSERT + publishEvent
 */
@DisplayName("DuplicateApprovalHandler")
class DuplicateApprovalHandlerTest {

    private static final String ORDER_ID = "order-dup-001";
    private static final BigDecimal PAYLOAD_AMOUNT = BigDecimal.valueOf(15000L);
    private static final long AMOUNT_LONG = 15000L;
    private static final long MISMATCH_AMOUNT_LONG = 9999L;

    private FakePgGatewayAdapter gatewayAdapter;
    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private ApplicationEventPublisher eventPublisher;
    private MeterRegistry meterRegistry;
    private DuplicateApprovalHandler handler;

    @BeforeEach
    void setUp() {
        gatewayAdapter = new FakePgGatewayAdapter();
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        // FakePgGatewayAdapter.supports(vendorType)=true(모든 벤더)라 selector 가 항상 반환한다.
        PgStatusLookupStrategySelector selector = new PgStatusLookupStrategySelector(List.of(gatewayAdapter));
        handler = new DuplicateApprovalHandler(
                selector, inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // pg DB 존재 + amount 일치 → 저장 status 재발행
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenPgDbExists_WhenAmountMatch_ShouldReemitStoredStatus")
    void pg_duplicate_approval_WhenPgDbExists_WhenAmountMatch_ShouldReemitStoredStatus() {
        // given — inbox APPROVED(stored_status_result 포함) 사전 설정
        String storedResult = "{\"orderId\":\"" + ORDER_ID + "\",\"status\":\"APPROVED\",\"amount\":" + AMOUNT_LONG + "}";
        PgInbox approvedInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT_LONG,
                storedResult, null, Instant.now(), Instant.now());
        inboxRepository.save(approvedInbox);

        // vendor getStatus → DONE + 동일 amount
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_outbox 1건, stored_status_result 기반 재발행(topic=events.confirmed)
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRows.get(0).getPayload()).isEqualTo(storedResult);

        // then — pg_inbox 상태 변경 없음(이미 terminal APPROVED)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

        // then — getStatusByOrderId 1회 호출
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // pg DB 존재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenPgDbExists_WhenAmountMismatch_ShouldQuarantine")
    void pg_duplicate_approval_WhenPgDbExists_WhenAmountMismatch_ShouldQuarantine() {
        // given — inbox IN_PROGRESS(amount=15000) 사전 설정 (중복 승인 처리 중 상태)
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        // vendor getStatus → DONE + 다른 amount(불일치)
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox QUARANTINED + reason_code=AMOUNT_MISMATCH
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("AMOUNT_MISMATCH");

        // then — pg_outbox 1건, QUARANTINED 페이로드
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("QUARANTINED");
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("AMOUNT_MISMATCH");

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // pg DB 존재 + amount 일치 + 종결 전 → 벤더 조회 결과로 종결 여부 분기
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("기록있음_금액일치_종결전_조회상태_승인_조회결과로_승인종결")
    void duplicateApproval_dbExists_amountMatch_unsettled_approvedStatus_settlesWithVendorResult() {
        // given — inbox IN_PROGRESS(종결 전), 벤더 조회 DONE + 동일 amount + 승인 시각 원문
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        String approvedAtRaw = "2026-04-24T10:15:30+09:00";
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, approvedAtRaw);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_outbox 1건, 승인 시각이 조회 원문과 동일
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).contains(approvedAtRaw);

        // then — pg_inbox APPROVED 로 종결
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    @Test
    @DisplayName("기록있음_금액일치_종결전_조회상태_승인아님_아무것도_하지않음")
    void duplicateApproval_dbExists_amountMatch_unsettled_notApprovedStatus_doesNothing() {
        // given — inbox IN_PROGRESS(종결 전), 벤더 조회는 성공했지만 상태가 승인이 아님
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.WAITING_FOR_DEPOSIT,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 상태 변경 없음, 발행 없음
        assertThat(outboxRepository.findAll()).isEmpty();
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("기록있음_종결전_승인종결_전이0건이면_발행행_미저장")
    void duplicateApproval_dbExists_unsettled_transitionBlocked_noOutboxRow() {
        // given — findByOrderId 시점엔 종결 전이지만, transitToApproved 시점엔 경합으로 이미 종결됐다고 가정
        PgInboxRepository mockInboxRepo = mock(PgInboxRepository.class);
        FakePgOutboxRepository racedOutboxRepository = new FakePgOutboxRepository();
        ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
        FakePgGatewayAdapter racedGatewayAdapter = new FakePgGatewayAdapter();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        PgStatusLookupStrategySelector selector =
                new PgStatusLookupStrategySelector(List.of(racedGatewayAdapter));
        DuplicateApprovalHandler racedHandler = new DuplicateApprovalHandler(
                selector, mockInboxRepo, racedOutboxRepository, mockPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock, new SimpleMeterRegistry());

        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.of(inProgressInbox));

        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, "2026-04-24T10:00:00+09:00");
        racedGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        when(mockInboxRepo.transitToApproved(eq(ORDER_ID), anyString())).thenReturn(0);

        // when
        racedHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 발행 행 자체가 만들어지지 않는다(폴링 안전망 우회 차단)
        assertThat(racedOutboxRepository.findAll()).isEmpty();
        verify(mockPublisher, never()).publishEvent(any());
    }

    // -----------------------------------------------------------------------
    // pg DB 존재 + amount 불일치 → 종결 여부와 무관하게 격리
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"IN_PROGRESS", "APPROVED"})
    @DisplayName("기록있음_금액불일치_종결여부와_무관하게_격리전이_시도")
    void duplicateApproval_dbExists_amountMismatch_attemptsQuarantineRegardlessOfSettlement(PgInboxStatus initialStatus) {
        // given
        PgInbox inbox = PgInbox.of(
                ORDER_ID, initialStatus, AMOUNT_LONG,
                initialStatus.isTerminal() ? "{\"status\":\"APPROVED\"}" : null, null,
                Instant.now(), Instant.now());
        inboxRepository.save(inbox);

        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        List<PgOutbox> outboxRows = outboxRepository.findAll();
        if (initialStatus.isTerminal()) {
            // then — 이미 종결된 기록은 격리 CAS 자체가 막혀 전이도 발행도 일어나지 않는다(가드)
            assertThat(outboxRows).isEmpty();
            PgInbox unchanged = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(initialStatus);
        } else {
            // then — 종결 전 기록은 금액 불일치 경로로 격리 전이 + 발행이 나간다
            assertThat(outboxRows).hasSize(1);
            assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("QUARANTINED");
            assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("AMOUNT_MISMATCH");
        }
    }

    @Test
    @DisplayName("금액불일치_격리전이_1건반영이면_격리발행")
    void duplicateApproval_dbExists_amountMismatch_transitioned_quarantinesAndPublishes() {
        // given — inbox IN_PROGRESS(종결 전), 벤더 조회 금액 불일치 → CAS 는 정상 1건 반영
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox QUARANTINED 전이 + pg_outbox 1건 + 발행
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(outboxRepository.findAll()).hasSize(1);
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    @Test
    @DisplayName("금액불일치_격리전이_0건반영이면_발행행_미저장")
    void duplicateApproval_dbExists_amountMismatch_transitionBlocked_noOutboxRow() {
        // given — findByOrderId 시점엔 종결 전이지만, transitToQuarantined 시점엔 경합으로 이미 종결됐다고 가정
        PgInboxRepository mockInboxRepo = mock(PgInboxRepository.class);
        FakePgOutboxRepository racedOutboxRepository = new FakePgOutboxRepository();
        ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
        FakePgGatewayAdapter racedGatewayAdapter = new FakePgGatewayAdapter();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        PgStatusLookupStrategySelector selector =
                new PgStatusLookupStrategySelector(List.of(racedGatewayAdapter));
        DuplicateApprovalHandler racedHandler = new DuplicateApprovalHandler(
                selector, mockInboxRepo, racedOutboxRepository, mockPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock, new SimpleMeterRegistry());

        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.of(inProgressInbox));

        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
        racedGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        when(mockInboxRepo.transitToQuarantined(eq(ORDER_ID), eq("AMOUNT_MISMATCH"))).thenReturn(false);

        // when
        racedHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 발행 행 자체가 만들어지지 않는다(폴링 안전망 우회 차단)
        assertThat(racedOutboxRepository.findAll()).isEmpty();
        verify(mockPublisher, never()).publishEvent(any());
    }

    // -----------------------------------------------------------------------
    // pg DB 부재 + amount 일치 → APPROVED 기록 + 운영 알림
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove")
    void pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove() {
        // given — inbox 없음(pg DB 부재)
        // vendor getStatus → DONE + payload와 동일 amount
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox 신설 + APPROVED 상태
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

        // then — pg_outbox 1건, APPROVED 페이로드
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("APPROVED");

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    @Test
    @DisplayName("기록없음_금액일치_승인시각이_조회원문과_동일")
    void duplicateApproval_dbAbsent_amountMatch_approvedAtEqualsVendorRaw() {
        // given — inbox 없음(pg DB 부재), 벤더 조회 DONE + payload와 동일 amount + 승인 시각 원문
        String approvedAtRaw = "2026-04-24T19:45:00+09:00";
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null, approvedAtRaw);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_outbox 승인 시각이 조회 원문과 동일
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).contains(approvedAtRaw);

        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // pg DB 부재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMismatch_ShouldQuarantine")
    void pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMismatch_ShouldQuarantine() {
        // given — inbox 없음(pg DB 부재)
        // vendor getStatus → DONE + 다른 amount(불일치)
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox 신설 + QUARANTINED + reason_code=AMOUNT_MISMATCH
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("AMOUNT_MISMATCH");

        // then — pg_outbox 1건, QUARANTINED 페이로드
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("QUARANTINED");
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("AMOUNT_MISMATCH");

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // vendor.getStatus() 실패(timeout/5xx) — 기록 종결 여부로 물러남/보정 분기
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("조회실패_기록이_종결전이면_격리하지_않고_물러난다")
    void duplicateApproval_vendorIndeterminate_unsettledRecord_backsOffWithoutQuarantine() {
        // given — inbox IN_PROGRESS 상태(종결 전, 조회는 실패)
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout simulated"));

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 상태 변경 없음, 발행 없음(겹침이면 원 호출이, 좀비면 다음 폴링이 처리)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(outboxRepository.findAll()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("조회실패_물러날때_경고와_지표가_남는다")
    void duplicateApproval_vendorIndeterminate_backoff_incrementsCounter() {
        // given — inbox IN_PROGRESS 상태(종결 전, 조회는 실패)
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout simulated"));

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 물러남을 안전하다고 본 근거가 가시성이므로 전용 카운터 증가를 확인한다
        assertThat(meterRegistry.counter(DuplicateApprovalHandler.VENDOR_INDETERMINATE_BACKOFF_COUNTER_NAME).count())
                .as("vendor_indeterminate_backoff_total 카운터가 1 증가해야 한다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("조회실패_기록이_이미종결이면_격리도_발행도_하지않는다")
    void duplicateApproval_vendorIndeterminate_settledRecord_doesNotQuarantineOrPublish() {
        // given — inbox APPROVED(이미 종결), 조회는 실패
        String storedResult = "{\"orderId\":\"" + ORDER_ID + "\",\"status\":\"APPROVED\",\"amount\":" + AMOUNT_LONG + "}";
        PgInbox approvedInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT_LONG,
                storedResult, null, Instant.now(), Instant.now());
        inboxRepository.save(approvedInbox);

        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout simulated"));

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — 접수대장은 승인 그대로, 격리 전이도 발행도 일어나지 않는다
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(outboxRepository.findAll()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("조회실패_기록이_없으면_기존_보정을_유지한다")
    void duplicateApproval_vendorIndeterminate_absentRecord_keepsExistingQuarantineCorrection() {
        // given — inbox 없음(기록 신설 후 격리 경로는 이번 범위 밖)
        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout simulated"));

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox 신설 + QUARANTINED + reason_code=VENDOR_INDETERMINATE
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("VENDOR_INDETERMINATE");

        // then — pg_outbox 1건, QUARANTINED 페이로드
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("QUARANTINED");

        // then — PgOutboxReadyEvent 발행
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // =======================================================================
    // 보정 경로 PENDING 우회 + atomicity 검증 (Mockito 기반)
    // =======================================================================

    /**
     * 보정 경로가 PENDING 을 우회해 신규 전이 메서드를 호출하는지 검증한다.
     * Mockito 기반으로 포트 메서드 호출 여부를 직접 검증한다.
     */
    @Nested
    @DisplayName("보정 경로 PENDING 우회 검증 (Mockito)")
    class Pcs9MockTests {

        private PgInboxRepository mockInboxRepo;
        private PgOutboxRepository mockOutboxRepo;
        private ApplicationEventPublisher mockPublisher;
        private DuplicateApprovalHandler mockHandler;
        private FakePgGatewayAdapter mockGatewayAdapter;
        private Clock fixedClock;

        @BeforeEach
        void setUp() {
            mockInboxRepo = mock(PgInboxRepository.class);
            mockOutboxRepo = mock(PgOutboxRepository.class);
            mockPublisher = mock(ApplicationEventPublisher.class);
            mockGatewayAdapter = new FakePgGatewayAdapter();
            fixedClock = Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC);

            PgStatusLookupStrategySelector selector =
                    new PgStatusLookupStrategySelector(List.of(mockGatewayAdapter));

            mockHandler = new DuplicateApprovalHandler(
                    selector, mockInboxRepo, mockOutboxRepo, mockPublisher,
                    new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock, new SimpleMeterRegistry());

            // outbox save stub — 저장된 outbox 반환 (publishEvent 에서 id 필요)
            when(mockOutboxRepo.save(any())).thenAnswer(inv -> {
                PgOutbox o = inv.getArgument(0);
                return PgOutbox.of(99L, o.getTopic(), o.getKey(), o.getPayload(), null,
                        Instant.now(), null, Instant.now());
            });
        }

        // -----------------------------------------------------------------------
        // handleDbAbsentAmountMatch → transitDirectToInProgress + transitToApproved
        //            (transitNoneToInProgress 미호출)
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("handleDbAbsentAmountMatch — transitDirectToTerminal(APPROVED) 호출, transitNoneToInProgress 미호출")
        void handleDbAbsentAmountMatch_transitsDirectToApproved() {
            // given — inbox 없음
            when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

            // vendor getStatus → DONE + amount 일치
            PgStatusResult vendorStatus = new PgStatusResult(
                    "pk-001", ORDER_ID, PgPaymentStatus.DONE,
                    BigDecimal.valueOf(AMOUNT_LONG), null, null, null);
            mockGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

            // transitDirectToTerminal stub
            when(mockInboxRepo.transitDirectToTerminal(
                    eq(ORDER_ID), eq(AMOUNT_LONG), eq(PgInboxStatus.APPROVED), anyString(), any()))
                    .thenReturn(1L);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — transitDirectToTerminal(APPROVED) 호출
            verify(mockInboxRepo, times(1)).transitDirectToTerminal(
                    eq(ORDER_ID), eq(AMOUNT_LONG), eq(PgInboxStatus.APPROVED), anyString(), any());
        }

        // -----------------------------------------------------------------------
        // handleDbAbsentAmountMismatch → transitDirectToTerminal(QUARANTINED)
        //            (transitNoneToInProgress 미호출)
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("handleDbAbsentAmountMismatch — transitDirectToTerminal(QUARANTINED) 호출, PENDING 미경유")
        void handleDbAbsentAmountMismatch_transitsDirectToQuarantined() {
            // given — inbox 없음
            when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

            // vendor getStatus → DONE + amount 불일치
            PgStatusResult vendorStatus = new PgStatusResult(
                    "pk-001", ORDER_ID, PgPaymentStatus.DONE,
                    BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null, null);
            mockGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

            // transitDirectToTerminal stub
            when(mockInboxRepo.transitDirectToTerminal(
                    eq(ORDER_ID), anyLong(), eq(PgInboxStatus.QUARANTINED), anyString(), anyString()))
                    .thenReturn(1L);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — transitDirectToTerminal(QUARANTINED) 호출
            verify(mockInboxRepo, times(1)).transitDirectToTerminal(
                    eq(ORDER_ID), anyLong(), eq(PgInboxStatus.QUARANTINED), anyString(), anyString());
        }

        // -----------------------------------------------------------------------
        // handleVendorIndeterminate (inbox 없음) → transitDirectToInProgress
        //            (PENDING 우회 검증)
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("handleVendorIndeterminate (inbox 없음) — transitDirectToInProgress 호출, PENDING 미경유")
        void handleVendorIndeterminate_absent_transitsDirectToInProgress() {
            // given — inbox 없음
            when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

            // vendor getStatus → 실패
            mockGatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout"));

            // transitDirectToInProgress stub
            when(mockInboxRepo.transitDirectToInProgress(eq(ORDER_ID), eq(AMOUNT_LONG)))
                    .thenReturn(1L);

            // then — transitToQuarantined stub
            when(mockInboxRepo.transitToQuarantined(eq(ORDER_ID), anyString())).thenReturn(true);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — transitDirectToInProgress 호출 (PENDING 우회)
            verify(mockInboxRepo, times(1)).transitDirectToInProgress(eq(ORDER_ID), eq(AMOUNT_LONG));
        }

        // -----------------------------------------------------------------------
        // handleVendorAlreadyProcessed — IN_PROGRESS inbox + amount 일치 → APPROVED
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("handleVendorAlreadyProcessed — IN_PROGRESS inbox + amount 일치 → transitDirectToTerminal(APPROVED), PENDING 미경유")
        void handleVendorAlreadyProcessed_inProgressInbox_amountMatch_transitsToApproved() {
            // given — IN_PROGRESS inbox 존재
            PgInbox inProgressInbox = PgInbox.of(
                    ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                    null, null, Instant.now(), Instant.now());
            when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.of(inProgressInbox));

            // vendor getStatus → DONE + amount 일치
            PgStatusResult vendorStatus = new PgStatusResult(
                    "pk-001", ORDER_ID, PgPaymentStatus.DONE,
                    BigDecimal.valueOf(AMOUNT_LONG), null, null, null);
            mockGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

            // 종결 전 기록 + 조회 상태 승인 → transitToApproved CAS 로 종결
            when(mockInboxRepo.transitToApproved(eq(ORDER_ID), anyString())).thenReturn(1);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — outbox 저장(조회 결과 기반 승인 종결) — DB exists 경로
            verify(mockOutboxRepo, times(1)).save(any());
            // then — transitNoneToInProgress 미호출
            verify(mockInboxRepo, never()).transitDirectToInProgress(anyString(), anyLong());
        }

        // -----------------------------------------------------------------------
        // handleVendorIndeterminate atomicity — transitDirectToInProgress +
        //            transitToQuarantined 두 호출이 같은 @Transactional TX 안
        //            Mockito inOrder 로 호출 순서 검증 + @Transactional 봉인
        // -----------------------------------------------------------------------

        @Test
        @DisplayName("handleVendorIndeterminate — transitDirectToInProgress + transitToQuarantined 순서 보장 (atomicity 봉인)")
        void handleVendorIndeterminate_atomicity_singleTransaction() {
            // given — inbox 없음, vendor 실패
            when(mockInboxRepo.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
            mockGatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout"));

            when(mockInboxRepo.transitDirectToInProgress(eq(ORDER_ID), eq(AMOUNT_LONG)))
                    .thenReturn(1L);
            when(mockInboxRepo.transitToQuarantined(eq(ORDER_ID), anyString())).thenReturn(true);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — 호출 순서: transitDirectToInProgress → transitToQuarantined
            InOrder order = inOrder(mockInboxRepo);
            order.verify(mockInboxRepo).transitDirectToInProgress(eq(ORDER_ID), eq(AMOUNT_LONG));
            order.verify(mockInboxRepo).transitToQuarantined(eq(ORDER_ID), anyString());
        }
    }

}
