package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;

/**
 * PgFinalConfirmationGate 단위 테스트.
 * 재시도 루프 소진 후 벤더 getStatus 단 1회 호출로 최종 상태를 확정한다 (FCG 불변).
 * APPROVED/FAILED 는 pg_outbox(events.confirmed) 로 흐르고, 판정 불가는 무조건 QUARANTINED (재시도 없음).
 * domain_risk=true — 1회 호출 보장 + QUARANTINED 전이 원자성 커버.
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
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        // FakePgGatewayAdapter.supports(vendorType)=true(모든 벤더)라 selector 가 항상 반환한다.
        PgStatusLookupStrategySelector selector = new PgStatusLookupStrategySelector(List.of(gatewayAdapter));
        fcg = new PgFinalConfirmationGate(
                selector, inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock);

        // inbox를 IN_PROGRESS 상태로 사전 설정 (재시도 소진 직후 상태)
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);
    }

    // -----------------------------------------------------------------------
    // 벤더 getStatus 최종 확인 APPROVED → pg_outbox(APPROVED) INSERT + pg_inbox APPROVED 전이
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus APPROVED 반환 시 pg_outbox(APPROVED) INSERT + pg_inbox APPROVED 전이")
    void fcg_WhenVendorReturnsApproved_ShouldInsertApprovedOutboxRow() {
        // given — getStatusByOrderId → DONE(APPROVED 매핑)
        PgStatusResult approvedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, approvedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

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
    // 벤더 getStatus 확정 실패(FAILED) → pg_outbox(FAILED) INSERT + pg_inbox FAILED 전이
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus FAILED 반환 시 pg_outbox(FAILED) INSERT + pg_inbox FAILED 전이")
    void fcg_WhenVendorReturnsFailed_ShouldInsertFailedOutboxRow() {
        // given — getStatusByOrderId → ABORTED(FAILED 매핑)
        PgStatusResult failedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.ABORTED,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, failedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

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
    // 벤더 timeout → QUARANTINED + FCG_INDETERMINATE. getStatus 호출 1회만 (재시도 0회)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus timeout 시 QUARANTINED + FCG_INDETERMINATE reason_code, getStatus 1회만 호출 (FCG 불변)")
    void fcg_WhenVendorTimesOut_ShouldQuarantine_NoRetry() {
        // given — getStatusByOrderId → PgGatewayRetryableException (timeout 시뮬레이션)
        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

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
    // 5xx/네트워크 에러 → QUARANTINED + FCG_INDETERMINATE. 재시도 0회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더 getStatus 5xx/네트워크 에러 시 QUARANTINED + FCG_INDETERMINATE, 재시도 0회 (FCG 불변)")
    void fcg_WhenVendor5xx_ShouldQuarantine() {
        // given — getStatusByOrderId → PgGatewayNonRetryableException (5xx/네트워크 에러 시뮬레이션)
        gatewayAdapter.throwOnStatusQuery(PgGatewayNonRetryableException.of("5xx server error"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

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

    // -----------------------------------------------------------------------
    // 금액 대조가 상태 판정보다 먼저 — 조회 금액이 접수 금액과 다르면 승인 응답이어도 격리
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 조회 금액이 접수 금액과 다르면 상태가 APPROVED여도 AMOUNT_MISMATCH로 격리")
    void 관문_조회금액이_접수금액과_다르면_금액불일치로_격리() {
        // given — 조회 응답 금액이 접수 금액(AMOUNT)과 다름, 상태는 DONE(승인)
        PgStatusResult mismatchedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT + 1000), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, mismatchedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — 승인 상태였어도 승인 종결로 가지 않고 QUARANTINED + AMOUNT_MISMATCH
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("AMOUNT_MISMATCH");

        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("AMOUNT_MISMATCH");

        // then — getStatusByOrderId 정확히 1회 호출
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 부분 취소 — 확정 실패로 접지 않고 전용 사유로 격리 (회귀 고정)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더가 부분 취소로 응답하면 FAILED로 확정하지 않고 FCG_PARTIAL_CANCELED로 격리")
    void 관문_부분취소응답_실패확정하지_않고_전용사유로_격리() {
        // given
        PgStatusResult partialCanceledStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.PARTIAL_CANCELED,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, partialCanceledStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — 부분 취소는 확정 실패(FAILED)로 접지 않는다. 재고를 즉시 풀면 안 되는
        // 시나리오라 사람 판단으로 넘기는 전용 사유로 격리한다.
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("FCG_PARTIAL_CANCELED");

        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("QUARANTINED");
    }

    // -----------------------------------------------------------------------
    // 취소·중단·만료 — 셋 다 확정 실패 종결
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PgPaymentStatus.class, names = {"CANCELED", "ABORTED", "EXPIRED"})
    @DisplayName("fcg — 취소·중단·만료 응답은 각각 FAILED로 확정 종결")
    void 관문_취소_중단_만료_각각_실패종결(PgPaymentStatus status) {
        // given
        PgStatusResult failedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, status,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, failedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);

        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).containsIgnoringCase("FAILED");
    }

    // -----------------------------------------------------------------------
    // 입금 대기 등 미확정 상태 — 벤더가 아직 결론을 내지 않은 경우
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 벤더가 입금 대기로 응답하면 FCG_VENDOR_UNSETTLED로 격리")
    void 관문_입금대기응답_벤더미결론_사유로_격리() {
        // given
        PgStatusResult waitingStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.WAITING_FOR_DEPOSIT,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, waitingStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("FCG_VENDOR_UNSETTLED");
    }

    // -----------------------------------------------------------------------
    // 조회 자체의 실행 시 예외 — 게이트웨이 예외가 아니어도 호출자까지 새어나가지 않는다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 조회가 게이트웨이 예외가 아닌 실행 시 예외를 던져도 FCG_INDETERMINATE로 격리")
    void 관문_조회가_게이트웨이예외가_아닌_실행시예외를_던져도_조회실패로_격리() {
        // given — PgGatewayRetryableException/NonRetryableException 이 아닌 임의의 실행 시 예외.
        // 처리 기록 없는 주문에 벤더 전략이 던질 수 있는 예외를 모사한다.
        gatewayAdapter.throwOnStatusQuery(new UnsupportedOperationException("처리 기록 없는 orderId"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — 예외가 호출자까지 새어나가지 않고 QUARANTINED + FCG_INDETERMINATE 로 흡수
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("FCG_INDETERMINATE");

        // then — getStatusByOrderId 정확히 1회만 호출 (FCG 불변)
        assertThat(gatewayAdapter.getStatusCallCount()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // 전이 반영 0건 — 경합으로 이미 종결된 기록이면 발행 행 자체를 만들지 않는다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 승인 전이 반영 행 수가 0이면 발행 행을 만들지 않는다")
    void 관문_승인전이_반영0건이면_발행행을_만들지_않는다() {
        // given — 경합으로 이미 종결된 inbox (APPROVED)
        inboxRepository.save(PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT,
                "{}", null, Instant.now(), Instant.now()));
        PgStatusResult approvedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, approvedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — 발행 행이 생기지 않고 이벤트도 발행되지 않는다
        assertThat(outboxRepository.findAll()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(PgOutboxReadyEvent.class));
    }

    @Test
    @DisplayName("fcg — 실패 전이 반영 행 수가 0이면 발행 행을 만들지 않는다")
    void 관문_실패전이_반영0건이면_발행행을_만들지_않는다() {
        // given — 경합으로 이미 종결된 inbox (FAILED)
        inboxRepository.save(PgInbox.of(
                ORDER_ID, PgInboxStatus.FAILED, AMOUNT,
                "{}", "OTHER_REASON", Instant.now(), Instant.now()));
        PgStatusResult canceledStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.CANCELED,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, canceledStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then
        assertThat(outboxRepository.findAll()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(PgOutboxReadyEvent.class));
    }

    @Test
    @DisplayName("fcg — 격리 전이 반영 행 수가 0이면 발행 행을 만들지 않는다")
    void 관문_격리전이_반영0건이면_발행행을_만들지_않는다() {
        // given — 경합으로 이미 종결된 inbox (QUARANTINED), 조회는 실행 시 예외로 실패
        inboxRepository.save(PgInbox.of(
                ORDER_ID, PgInboxStatus.QUARANTINED, AMOUNT,
                null, "OTHER_REASON", Instant.now(), Instant.now()));
        gatewayAdapter.throwOnStatusQuery(PgGatewayRetryableException.of("timeout"));

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then
        assertThat(outboxRepository.findAll()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(PgOutboxReadyEvent.class));
    }

    // -----------------------------------------------------------------------
    // 승인 시각 원문 — 조회 응답 원문을 승인 페이로드까지 전달
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("fcg — 승인 페이로드에 조회 응답의 승인 시각 원문이 그대로 실린다")
    void 관문_승인페이로드에_조회응답_승인시각_원문이_실린다() {
        // given — 조회 응답에 승인 시각 원문이 실려 있음
        String approvedAtRaw = "2026-04-24T10:15:30+09:00";
        PgStatusResult approvedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT), null, null, approvedAtRaw);
        gatewayAdapter.setStatusResult(ORDER_ID, approvedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — fixedClock 기반 현재 시각으로 대체되지 않고 조회 응답 원문 그대로 실린다
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).contains(approvedAtRaw);
    }

    @Test
    @DisplayName("fcg — 조회 응답에 승인 시각 원문이 없으면 현재 시각으로 대체한다")
    void 관문_조회응답에_승인시각이_없으면_현재시각으로_대체한다() {
        // given — 조회 응답에 승인 시각 원문이 없음(null)
        PgStatusResult approvedStatus = new PgStatusResult(
                "pk-fcg-001", ORDER_ID, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT), null, null, null);
        gatewayAdapter.setStatusResult(ORDER_ID, approvedStatus);

        // when
        fcg.performFinalCheck(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

        // then — fixedClock("2026-04-24T01:00:00Z") 기반 현재 시각으로 대체된다
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).contains("2026-04-24T01:00Z");
    }
}
