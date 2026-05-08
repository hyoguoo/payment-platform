package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PgVendorCallService 단위 테스트 — PCS-6 재작성.
 *
 * <p>invokeVendor (TX 없음, 벤더 HTTP) + applyOutcome (@Transactional TX_B, 5분기) 분리 검증.
 * domain_risk=true — TX 경계 분리 + 5분기 결과 반영 정밀 검증.
 */
@DisplayName("PgVendorCallService")
class PgVendorCallServiceTest {

    private static final String ORDER_ID = "order-vendor-001";
    private static final String PAYMENT_KEY = "pk-vendor-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(15000);

    private static final Instant NOW = Instant.parse("2026-04-21T00:00:00Z");

    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private FakePgGatewayAdapter gatewayAdapter;
    private ApplicationEventPublisher eventPublisher;
    private DuplicateApprovalHandler duplicateApprovalHandler;
    private PgVendorCallService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        gatewayAdapter = new FakePgGatewayAdapter();
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        duplicateApprovalHandler = Mockito.mock(DuplicateApprovalHandler.class);

        ObjectMapper objectMapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        PgConfirmStrategySelector selector = new PgConfirmStrategySelector(List.of(gatewayAdapter));

        sut = new PgVendorCallService(
                inboxRepository, outboxRepository, selector,
                eventPublisher, new ConfirmedEventPayloadSerializer(objectMapper),
                objectMapper, fixedClock, duplicateApprovalHandler);

        // inbox를 IN_PROGRESS 상태로 사전 준비 (applyOutcome 진입 전제조건)
        inboxRepository.save(PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, NOW, NOW));
    }

    // -----------------------------------------------------------------------
    // invokeVendor — TX 없음 경로
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("invokeVendor")
    class InvokeVendor {

        @Test
        @DisplayName("성공 응답 — PgConfirmPort.confirm() 1회 호출 + GatewayOutcome.Success 반환")
        void invokeVendor_callsConfirmPort_returnsGatewayOutcome() {
            // given
            PgConfirmResult successResult = new PgConfirmResult(
                    PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                    "2026-04-24T01:00:00Z");
            gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

            // when
            GatewayOutcome outcome = sut.invokeVendor(buildRequest(ORDER_ID));

            // then
            assertThat(outcome).isInstanceOf(GatewayOutcome.Success.class);
            assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PgGatewayRetryableException → GatewayOutcome.Retryable 반환")
        void invokeVendor_retryableException_returnsRetryableOutcome() {
            // given
            gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("network timeout"));

            // when
            GatewayOutcome outcome = sut.invokeVendor(buildRequest(ORDER_ID));

            // then
            assertThat(outcome).isInstanceOf(GatewayOutcome.Retryable.class);
        }

        @Test
        @DisplayName("PgGatewayNonRetryableException → GatewayOutcome.NonRetryable 반환")
        void invokeVendor_nonRetryableException_returnsNonRetryableOutcome() {
            // given
            gatewayAdapter.throwOnConfirm(PgGatewayNonRetryableException.of("card_declined"));

            // when
            GatewayOutcome outcome = sut.invokeVendor(buildRequest(ORDER_ID));

            // then
            assertThat(outcome).isInstanceOf(GatewayOutcome.NonRetryable.class);
        }

        @Test
        @DisplayName("PgGatewayDuplicateHandledException → GatewayOutcome.HandledInternally 반환")
        void invokeVendor_duplicateException_returnsHandledInternally() {
            // given
            gatewayAdapter.throwOnConfirm(PgGatewayDuplicateHandledException.of("ALREADY_PROCESSED"));

            // when
            GatewayOutcome outcome = sut.invokeVendor(buildRequest(ORDER_ID));

            // then
            assertThat(outcome).isInstanceOf(GatewayOutcome.HandledInternally.class);
        }
    }

    // -----------------------------------------------------------------------
    // applyOutcome — TX_B 5분기
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("applyOutcome")
    class ApplyOutcome {

        @Test
        @DisplayName("Success outcome → pg_inbox APPROVED + pg_outbox payment.events.confirmed INSERT")
        void applyOutcome_success_transitsToApproved() {
            // given
            PgConfirmResult result = new PgConfirmResult(
                    PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                    "2026-04-24T01:00:00Z");
            GatewayOutcome outcome = new GatewayOutcome.Success(result);

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — inbox APPROVED
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

            // then — outbox 1건: topic=payment.events.confirmed
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
            assertThat(rows.get(0).getPayload()).containsIgnoringCase("APPROVED");
        }

        @Test
        @DisplayName("Retryable outcome (잔여 시도) → pg_outbox 재시도 명령 INSERT, pg_inbox IN_PROGRESS 유지")
        void applyOutcome_retryable_insertsRetryCommand() {
            // given — attempt=1 (잔여 시도 있음)
            GatewayOutcome outcome = new GatewayOutcome.Retryable("network timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — outbox 1건: topic=payment.commands.confirm (재시도)
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM);
            assertThat(rows.get(0).getAvailableAt()).isAfter(NOW);

            // then — inbox IN_PROGRESS 유지
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("Retryable outcome (시도 소진) → pg_outbox DLQ 명령 INSERT, pg_inbox IN_PROGRESS 유지")
        void applyOutcome_retryExhausted_insertsDlqCommand_inboxStaysInProgress() {
            // given — attempt=MAX (시도 소진)
            GatewayOutcome outcome = new GatewayOutcome.Retryable("upstream timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), RetryPolicy.MAX_ATTEMPTS, NOW);

            // then — outbox 1건: topic=payment.commands.confirm.dlq
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM_DLQ);

            // then — inbox IN_PROGRESS 유지 (QUARANTINED 전이는 DLQ consumer 책임)
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("NonRetryable outcome → pg_inbox FAILED + pg_outbox payment.events.confirmed INSERT")
        void applyOutcome_nonRetryable_transitsToFailed() {
            // given
            GatewayOutcome outcome = new GatewayOutcome.NonRetryable("card_declined");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — inbox FAILED
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);

            // then — outbox 1건: topic=payment.events.confirmed
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
            assertThat(rows.get(0).getPayload()).containsIgnoringCase("FAILED");
        }

        @Test
        @DisplayName("HandledInternally outcome → DuplicateApprovalHandler.handleDuplicateApproval 1회 위임")
        void applyOutcome_handledInternally_delegatesToDuplicateHandler() {
            // given
            GatewayOutcome outcome = new GatewayOutcome.HandledInternally("ALREADY_PROCESSED");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — DuplicateApprovalHandler.handleDuplicateApproval 1회 호출
            verify(duplicateApprovalHandler, times(1))
                    .handleDuplicateApproval(
                            Mockito.eq(ORDER_ID),
                            Mockito.eq(AMOUNT),
                            Mockito.eq(PgVendorType.TOSS));
        }
    }

    // -----------------------------------------------------------------------
    // 기존 callVendor 회귀 테스트 (deprecated 하되 기능 보존 확인)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — 벤더 호출 성공 시 payment.events.confirmed + status=APPROVED outbox row 1건 INSERT (회귀)")
    void callVendor_WhenSuccess_ShouldInsertApprovedOutboxRow() {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                "2026-04-24T01:00:00Z");
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        // when
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, NOW);

        // then
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(rows.get(0).getPayload()).containsIgnoringCase("APPROVED");

        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
    }

    @Test
    @DisplayName("callVendor — retryable 오류 + attempt=1 → payment.commands.confirm + available_at > now + attempt=2 header (회귀)")
    void callVendor_WhenRetryableErrorAndAttemptNotExceeded_ShouldInsertRetryOutboxRow() {
        // given
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("network timeout"));

        // when — attempt=1
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, NOW);

        // then
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM);
        assertThat(rows.get(0).getAvailableAt()).isAfter(NOW);
        assertThat(rows.get(0).getHeadersJson()).contains("\"attempt\":2");

        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("callVendor — retryable 오류 + attempt=4 → payment.commands.confirm.dlq + attempt=4 header (회귀)")
    void callVendor_WhenRetryableErrorAndAttemptExceeded_ShouldInsertDlqOutboxRow() {
        // given
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));

        // when — attempt=4 (MAX)
        sut.callVendor(buildRequest(ORDER_ID, 4), 4, NOW);

        // then
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM_DLQ);
        assertThat(rows.get(0).getHeadersJson()).contains("\"attempt\":4");
    }

    @Test
    @DisplayName("callVendor — non-retryable 확정 실패 시 payment.events.confirmed + status=FAILED outbox row 1건 INSERT (회귀)")
    void callVendor_WhenDefinitiveFailure_ShouldInsertFailedOutboxRow() {
        // given
        gatewayAdapter.throwOnConfirm(PgGatewayNonRetryableException.of("card_declined"));

        // when
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, NOW);

        // then
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(rows.get(0).getPayload()).containsIgnoringCase("FAILED");

        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
    }

    @Test
    @DisplayName("retry — attempt 소진 경계에서 DLQ outbox row INSERT와 pg_inbox 상태 기록 원자성 검증 (회귀)")
    void retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow() {
        // given
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("max attempt reached"));

        // when
        sut.callVendor(buildRequest(ORDER_ID, RetryPolicy.MAX_ATTEMPTS), RetryPolicy.MAX_ATTEMPTS, NOW);

        // then — DLQ row 정확히 1건
        List<PgOutbox> rows = outboxRepository.findAll();
        long dlqCount = rows.stream()
                .filter(r -> PgTopics.COMMANDS_CONFIRM_DLQ.equals(r.getTopic()))
                .count();
        assertThat(dlqCount).isEqualTo(1);

        PgOutbox dlqRow = rows.stream()
                .filter(r -> PgTopics.COMMANDS_CONFIRM_DLQ.equals(r.getTopic()))
                .findFirst()
                .orElseThrow();
        assertThat(dlqRow.getHeadersJson()).contains("\"attempt\":" + RetryPolicy.MAX_ATTEMPTS);

        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    private PgConfirmRequest buildRequest(String orderId) {
        return new PgConfirmRequest(orderId, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS);
    }

    private PgConfirmRequest buildRequest(String orderId, int attempt) {
        return new PgConfirmRequest(orderId, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS);
    }
}
