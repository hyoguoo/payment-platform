package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DuplicateApprovalHandler 단위 테스트.
 * ADR-05 보강 + ADR-21(캡슐화 대상): 중복 승인 응답 2자 금액 대조 방어.
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
    private static final String EVENT_UUID = "evt-dup-uuid-001";
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
        handler = new DuplicateApprovalHandler(
                gatewayAdapter, inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock);
    }

    // -----------------------------------------------------------------------
    // TC1: pg DB 존재 + amount 일치 → 저장 status 재발행
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
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, EVENT_UUID);

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
    // TC2: pg DB 존재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH
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
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, EVENT_UUID);

        // then — pg_inbox QUARANTINED + reason_code=AMOUNT_MISMATCH (불변식 4c)
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
    // TC3: pg DB 부재 + amount 일치 → APPROVED 기록 + 운영 알림
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
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, EVENT_UUID);

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
    // TC4: pg DB 부재 + amount 불일치 → QUARANTINED+AMOUNT_MISMATCH
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
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, EVENT_UUID);

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
    // TC5: vendor.getStatus() 실패(timeout/5xx) → QUARANTINED+VENDOR_INDETERMINATE
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
        handler.handleDuplicateApproval(ORDER_ID, PAYLOAD_AMOUNT, EVENT_UUID);

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

}
