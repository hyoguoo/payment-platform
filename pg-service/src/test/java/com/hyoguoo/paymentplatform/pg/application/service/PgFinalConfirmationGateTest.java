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
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

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
        PgFinalConfirmationGate.FcgOutcome outcome =
                fcg.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        fcg.applyOutcome(outcome, ORDER_ID, AMOUNT);

        // then — fixedClock("2026-04-24T01:00:00Z") 기반 현재 시각으로 대체된다
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getPayload()).contains("2026-04-24T01:00Z");
    }

    // -----------------------------------------------------------------------
    // TX 경계 분리 — invokeVendor(조회)는 TX 없음, applyOutcome(반영)만 TX 안에서 실행
    // PgInboxPendingServiceTest 의 TransactionSynchronizationManager + 전용 테스트 설정 방식을 따른다.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("TX 경계 분리 검증 (Spring TX proxy)")
    @ExtendWith(SpringExtension.class)
    @ContextConfiguration(classes = {
            TxBoundaryTestConfig.class,
            PgFinalConfirmationGate.class
    })
    class TxBoundaryTests {

        @Autowired
        private PgFinalConfirmationGate sut;

        @BeforeEach
        void resetCapture() {
            TxBoundaryTestConfig.CapturingStatusLookupPort.reset();
            TxBoundaryTestConfig.CapturingInboxRepository.reset();
        }

        @Test
        @DisplayName("invokeVendor — 조회 단계는 TX 를 열지 않는다")
        void 관문_조회단계는_트랜잭션을_열지_않는다() {
            // when — @Transactional 이 없는 invokeVendor 를 Spring 프록시 경유로 호출
            sut.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

            // then — 벤더 조회 시점에 active TX 가 없어야 한다
            assertThat(TxBoundaryTestConfig.CapturingStatusLookupPort.TX_ACTIVE_AT_QUERY.get())
                    .as("invokeVendor 는 TX 경계를 열지 않아야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("applyOutcome — 반영 단계만 TX 안에서 실행된다")
        void 관문_반영단계만_트랜잭션안에서_실행된다() {
            // given
            PgFinalConfirmationGate.FcgOutcome outcome =
                    sut.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);

            // when — @Transactional 이 붙은 applyOutcome 를 Spring 프록시 경유로 호출
            sut.applyOutcome(outcome, ORDER_ID, AMOUNT);

            // then — pg_inbox 전이 시점에 active TX 가 있어야 한다
            assertThat(TxBoundaryTestConfig.CapturingInboxRepository.TX_ACTIVE_AT_TRANSIT.get())
                    .as("applyOutcome 은 TX 안에서 반영을 수행해야 한다")
                    .isTrue();
        }
    }

    /**
     * TX 경계 분리 검증 전용 Spring 설정 — DB 없이 TX 동기화만 수행하는 최소 구성.
     * PgInboxPendingServiceTest.TxIntegrationTestConfig 와 동일 구조.
     */
    @TestConfiguration
    @EnableTransactionManagement
    static class TxBoundaryTestConfig {

        @Bean
        PgStatusLookupStrategySelector pgStatusLookupStrategySelector() {
            return new PgStatusLookupStrategySelector(List.of(new CapturingStatusLookupPort()));
        }

        @Bean
        PgInboxRepository pgInboxRepository() {
            return new CapturingInboxRepository();
        }

        @Bean
        PgOutboxRepository pgOutboxRepository() {
            return new NoopOutboxRepository();
        }

        @Bean
        ApplicationEventPublisher applicationEventPublisher() {
            return event -> { };
        }

        @Bean
        ConfirmedEventPayloadSerializer confirmedEventPayloadSerializer() {
            return new ConfirmedEventPayloadSerializer(new ObjectMapper());
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new SimplePlatformTransactionManager();
        }

        /**
         * 벤더 조회 시점에 active TX 여부를 캡처하는 Fake PgStatusLookupPort.
         * 항상 접수 금액과 일치하는 APPROVED 응답을 반환해 applyOutcome 이 transitToApproved 로 흐르게 한다.
         */
        static class CapturingStatusLookupPort implements PgStatusLookupPort {

            static final AtomicBoolean TX_ACTIVE_AT_QUERY = new AtomicBoolean(true);

            static void reset() {
                TX_ACTIVE_AT_QUERY.set(true);
            }

            @Override
            public boolean supports(PgVendorType vendorType) {
                return true;
            }

            @Override
            public PgStatusResult getStatusByOrderId(String orderId) {
                TX_ACTIVE_AT_QUERY.set(TransactionSynchronizationManager.isActualTransactionActive());
                return new PgStatusResult(
                        "pk-fcg-tx-001", orderId, PgPaymentStatus.DONE,
                        BigDecimal.valueOf(AMOUNT), null, null, null);
            }
        }

        /**
         * 반영 시점(transitToApproved)에 active TX 여부를 캡처하는 Fake PgInboxRepository.
         * 나머지 메서드는 이 검증에 쓰이지 않아 no-op 으로 둔다.
         */
        static class CapturingInboxRepository implements PgInboxRepository {

            static final AtomicBoolean TX_ACTIVE_AT_TRANSIT = new AtomicBoolean(false);

            static void reset() {
                TX_ACTIVE_AT_TRANSIT.set(false);
            }

            @Override
            public int transitToApproved(String orderId, String storedStatusResult) {
                TX_ACTIVE_AT_TRANSIT.set(TransactionSynchronizationManager.isActualTransactionActive());
                return 1;
            }

            @Override
            public Optional<PgInbox> findByOrderId(String orderId) {
                return Optional.empty();
            }

            @Override
            public Optional<PgInbox> findById(Long inboxId) {
                return Optional.empty();
            }

            @Override
            public PgInbox save(PgInbox inbox) {
                return inbox;
            }

            @Override
            public Long insertPending(String orderId, long amount,
                    String vendorType, String paymentKey, String storedTraceparent) {
                return null;
            }

            @Override
            public Optional<String> findStoredTraceparent(Long inboxId) {
                return Optional.empty();
            }

            @Override
            public boolean transitPendingToInProgress(Long inboxId) {
                return false;
            }

            @Override
            public Long transitDirectToInProgress(String orderId, long amount) {
                return null;
            }

            @Override
            public Long transitDirectToTerminal(String orderId, long amount,
                    PgInboxStatus terminalStatus, String storedStatusResult, String reasonCode) {
                return null;
            }

            @Override
            public List<Long> findPendingZombieIds(int batchSize, long thresholdMs) {
                return List.of();
            }

            @Override
            public List<Long> findInProgressZombieIds(int batchSize, long thresholdMs) {
                return List.of();
            }

            @Override
            public int transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
                return 0;
            }

            @Override
            public boolean transitToQuarantined(String orderId, String reasonCode) {
                return false;
            }

            @Override
            public Optional<PgInbox> findByOrderIdForUpdate(String orderId) {
                return Optional.empty();
            }

            @Override
            public Optional<PgInbox> selectInProgressForUpdateSkipLocked(Long inboxId) {
                return Optional.empty();
            }

            @Override
            public int incrementAttempt(String orderId) {
                return 0;
            }
        }

        /**
         * 반영 단계 pg_outbox INSERT 를 흡수만 하는 no-op Fake PgOutboxRepository.
         */
        static class NoopOutboxRepository implements PgOutboxRepository {

            @Override
            public PgOutbox save(PgOutbox outbox) {
                return outbox;
            }

            @Override
            public Optional<PgOutbox> findById(long id) {
                return Optional.empty();
            }

            @Override
            public List<PgOutbox> findPendingBatch(int batchSize, Instant now) {
                return List.of();
            }

            @Override
            public void markProcessed(long id, Instant processedAt) {
                // no-op
            }

            @Override
            public long countPending(Instant now) {
                return 0;
            }

            @Override
            public long countFuturePending(Instant now) {
                return 0;
            }

            @Override
            public Optional<Instant> findOldestPendingCreatedAt() {
                return Optional.empty();
            }

            @Override
            public List<PgOutbox> findConfirmAttemptRows(String orderId) {
                return List.of();
            }
        }

        /**
         * DB 없이 TX 동기화만 수행하는 최소 TX Manager.
         * TransactionSynchronizationManager.setActualTransactionActive(true) 를 통해
         * Spring @Transactional proxy 가 올바르게 동작함을 검증한다.
         */
        static class SimplePlatformTransactionManager extends AbstractPlatformTransactionManager {

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                // no-op — 실제 DB TX 없이 TX 동기화만 활성화
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // no-op
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // no-op
            }
        }
    }
}
