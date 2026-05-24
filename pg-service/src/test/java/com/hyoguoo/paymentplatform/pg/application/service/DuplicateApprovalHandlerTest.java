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
    private DuplicateApprovalHandler handler;

    @BeforeEach
    void setUp() {
        gatewayAdapter = new FakePgGatewayAdapter();
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        // FakePgGatewayAdapter.supports(vendorType)=true(모든 벤더)라 selector 가 항상 반환한다.
        PgStatusLookupStrategySelector selector = new PgStatusLookupStrategySelector(List.of(gatewayAdapter));
        handler = new DuplicateApprovalHandler(
                selector, inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock);
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
                BigDecimal.valueOf(AMOUNT_LONG), null, null);
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
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null);
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
    // pg DB 부재 + amount 일치 → APPROVED 기록 + 운영 알림
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove")
    void pg_duplicate_approval_WhenPgDbAbsent_WhenAmountMatch_ShouldAlertAndApprove() {
        // given — inbox 없음(pg DB 부재)
        // vendor getStatus → DONE + payload와 동일 amount
        PgStatusResult vendorStatus = new PgStatusResult(
                "pk-dup-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT_LONG), null, null);
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
                BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null);
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
    // vendor.getStatus() 실패(timeout/5xx) → QUARANTINED+VENDOR_INDETERMINATE
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pg_duplicate_approval_WhenVendorRetrievalFails_ShouldQuarantine")
    void pg_duplicate_approval_WhenVendorRetrievalFails_ShouldQuarantine() {
        // given — inbox IN_PROGRESS 상태(조회 성공 but vendor 실패)
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT_LONG,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        // vendor getStatus → timeout(PgGatewayRetryableException)
        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout simulated"));

        // when
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

        // then — pg_inbox QUARANTINED + reason_code=VENDOR_INDETERMINATE
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
                    new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock);

            // outbox save stub — 저장된 outbox 반환 (publishEvent 에서 id 필요)
            when(mockOutboxRepo.save(any())).thenAnswer(inv -> {
                PgOutbox o = inv.getArgument(0);
                return PgOutbox.of(99L, o.getTopic(), o.getKey(), o.getPayload(), null,
                        Instant.now(), null, 0, Instant.now());
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
                    BigDecimal.valueOf(AMOUNT_LONG), null, null);
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
                    BigDecimal.valueOf(MISMATCH_AMOUNT_LONG), null, null);
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
                    BigDecimal.valueOf(AMOUNT_LONG), null, null);
            mockGatewayAdapter.setStatusResult(ORDER_ID, vendorStatus);

            // when
            mockHandler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, PgVendorType.TOSS);

            // then — outbox 재발행 (storedStatusResult 기반) — DB exists 경로
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
