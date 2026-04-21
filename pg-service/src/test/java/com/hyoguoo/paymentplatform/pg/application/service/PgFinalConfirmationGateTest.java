package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;

/**
 * PgFinalConfirmationGate 단위 테스트.
 * ADR-15(FCG 불변): 재시도 루프 소진 후 벤더 getStatus 1회 최종 확인.
 * ADR-21: APPROVED/FAILED → pg_outbox(events.confirmed). 판정 불가 → 무조건 QUARANTINED (재시도 없음).
 * domain_risk=true: FCG 불변(1회만 호출, 재시도 래핑 금지) + QUARANTINED 전이 원자성 커버.
 */
@DisplayName("PgFinalConfirmationGate")
class PgFinalConfirmationGateTest {

    private static final String ORDER_ID = "order-fcg-001";
    private static final String EVENT_UUID = "evt-fcg-uuid-001";
    private static final long AMOUNT = 15000L;

    private FakePgGatewayAdapter gatewayAdapter;
    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private ApplicationEventPublisher eventPublisher;
    private PgFinalConfirmationGate fcg;

    @BeforeEach
    void setUp() {
        gatewayAdapter = new FakePgGatewayAdapter();
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);
        fcg = new PgFinalConfirmationGate(
                gatewayAdapter, inboxRepository, outboxRepository, eventPublisher);

        // inbox를 IN_PROGRESS 상태로 사전 설정 (재시도 소진 직후 상태)
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);
    }

    // -----------------------------------------------------------------------
    // TC1: 벤더 getStatus 최종 확인 APPROVED → pg_outbox(APPROVED) INSERT + pg_inbox APPROVED 전이
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus APPROVED 반환 시 pg_outbox(APPROVED) INSERT + pg_inbox APPROVED 전이 (ADR-15)")
    void fcg_WhenVendorReturnsApproved_ShouldInsertApprovedOutboxRow() {
        // given — getStatusByOrderId → DONE(APPROVED 매핑)
        PgStatusResult approvedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT), null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, approvedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT);

        // then — pg_inbox APPROVED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

        // then — pg_outbox row 1건, topic=payment.events.confirmed, APPROVED 포함
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox outboxRow = outboxRows.get(0);
        assertThat(outboxRow.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRow.getPayload()).containsIgnoringCase("APPROVED");

        // then — getStatusByOrderId 정확히 1회 호출 (FCG 불변: 재시도 래핑 금지)
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);

        // then — ApplicationEventPublisher 호출 (PgOutboxReadyEvent 발행)
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // TC2: 벤더 getStatus 확정 실패(FAILED) → pg_outbox(FAILED) INSERT + pg_inbox FAILED 전이
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus FAILED 반환 시 pg_outbox(FAILED) INSERT + pg_inbox FAILED 전이")
    void fcg_WhenVendorReturnsFailed_ShouldInsertFailedOutboxRow() {
        // given — getStatusByOrderId → ABORTED(FAILED 매핑)
        PgStatusResult failedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.ABORTED,
                BigDecimal.valueOf(AMOUNT), null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, failedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT);

        // then — pg_inbox FAILED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);

        // then — pg_outbox row 1건, FAILED 포함
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox outboxRow = outboxRows.get(0);
        assertThat(outboxRow.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRow.getPayload()).containsIgnoringCase("FAILED");

        // then — getStatusByOrderId 정확히 1회 호출
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);

        // then — ApplicationEventPublisher 호출
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // TC3: 벤더 timeout → QUARANTINED + FCG_INDETERMINATE. getStatus 호출 1회만 (재시도 0회)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus timeout 시 QUARANTINED + FCG_INDETERMINATE reason_code, getStatus 1회만 호출 (FCG 불변)")
    void fcg_WhenVendorTimesOut_ShouldQuarantine_NoRetry() {
        // given — getStatusByOrderId → PgGatewayRetryableException (timeout 시뮬레이션)
        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT);

        // then — pg_inbox QUARANTINED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("FCG_INDETERMINATE");

        // then — pg_outbox row 1건, QUARANTINED 포함
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox outboxRow = outboxRows.get(0);
        assertThat(outboxRow.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRow.getPayload()).containsIgnoringCase("QUARANTINED");

        // then — getStatusByOrderId 정확히 1회만 호출 (FCG 불변: 재시도 없음)
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);

        // then — ApplicationEventPublisher 호출
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // TC4: 5xx/네트워크 에러 → QUARANTINED + FCG_INDETERMINATE. 재시도 0회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus 5xx/네트워크 에러 시 QUARANTINED + FCG_INDETERMINATE, 재시도 0회 (FCG 불변)")
    void fcg_WhenVendor5xx_ShouldQuarantine() {
        // given — getStatusByOrderId → PgGatewayNonRetryableException (5xx/네트워크 에러 시뮬레이션)
        gatewayAdapter.throwOnStatusQuery(PgGatewayNonRetryableException.of("5xx server error"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT);

        // then — pg_inbox QUARANTINED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("FCG_INDETERMINATE");

        // then — pg_outbox row 1건, QUARANTINED 포함
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox outboxRow = outboxRows.get(0);
        assertThat(outboxRow.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRow.getPayload()).containsIgnoringCase("QUARANTINED");

        // then — getStatusByOrderId 정확히 1회 호출 (재시도 없음)
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);

        // then — ApplicationEventPublisher 호출
        verify(eventPublisher, times(1)).publishEvent(any(PgOutboxReadyEvent.class));
    }
}
