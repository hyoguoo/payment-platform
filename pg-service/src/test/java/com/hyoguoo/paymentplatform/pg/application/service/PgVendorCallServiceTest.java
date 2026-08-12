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
import com.hyoguoo.paymentplatform.pg.mock.FakeSecureRandom;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * PgVendorCallService 단위 테스트.
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

        // jitter factor = nextDouble()*0.5-0.25 → 0.5 를 고정 반환하면 jitter 0, backoff 가 base 값 그대로
        // 나와 회차별 대기 구간 검증이 실제 SecureRandom 흔들림 없이 결정적으로 돈다.
        sut = new PgVendorCallService(
                inboxRepository, outboxRepository, selector,
                eventPublisher, new ConfirmedEventPayloadSerializer(objectMapper),
                objectMapper, fixedClock, duplicateApprovalHandler, new FakeSecureRandom(0.5));

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
        @DisplayName("Retryable outcome (잔여 시도) → pg_outbox 재시도 명령 INSERT, pg_inbox IN_PROGRESS 유지 + attempt 1증가")
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

            // then — inbox IN_PROGRESS 유지 + attempt 1→2 증가 (TX_B 안에서 incrementAttempt)
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
            assertThat(inbox.getAttempt()).isEqualTo(2);
        }

        @Test
        @DisplayName("Retryable outcome (시도 소진) → pg_outbox DLQ 명령 INSERT, pg_inbox IN_PROGRESS 유지 + attempt 미증가")
        void applyOutcome_retryExhausted_insertsDlqCommand_inboxStaysInProgress() {
            // given — attempt=MAX (시도 소진)
            GatewayOutcome outcome = new GatewayOutcome.Retryable("upstream timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), RetryPolicy.MAX_ATTEMPTS, NOW);

            // then — outbox 1건: topic=payment.commands.confirm.dlq
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM_DLQ);

            // then — inbox IN_PROGRESS 유지 (QUARANTINED 전이는 DLQ consumer 책임) + attempt 미증가
            // (DLQ 분기는 incrementAttempt 미호출 — setUp 의 초기값 1 그대로 유지됨)
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
            assertThat(inbox.getAttempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("승인_전이_1건반영이면_발행행_저장과_이벤트발행")
        void applyOutcome_success_transitionApplied_savesOutboxAndPublishesEvent() {
            // given
            PgConfirmResult result = new PgConfirmResult(
                    PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                    "2026-04-24T01:00:00Z");
            GatewayOutcome outcome = new GatewayOutcome.Success(result);

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — 발행 행 저장 + 이벤트 발행
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
            verify(eventPublisher, times(1)).publishEvent(any());
        }

        @Test
        @DisplayName("승인_전이_0건반영이면_발행행_미저장")
        void applyOutcome_success_transitionBlocked_noOutboxRow() {
            // given — 좀비 회수 경합 등으로 이미 종결(FAILED)된 뒤 뒤늦게 승인 응답이 도착
            inboxRepository.transitToFailed(ORDER_ID, "{}", "ALREADY_FAILED");
            PgConfirmResult result = new PgConfirmResult(
                    PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                    "2026-04-24T01:00:00Z");
            GatewayOutcome outcome = new GatewayOutcome.Success(result);

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — 발행 행 자체가 만들어지지 않는다(폴링 안전망 우회 차단)
            assertThat(outboxRepository.findAll()).isEmpty();
            verify(eventPublisher, never()).publishEvent(any());

            // then — inbox 는 기존 종결 상태를 유지한다
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
        }

        @Test
        @DisplayName("가드 발동(이미 종결) 시 재시도 outbox INSERT + 발행 이벤트를 하지 않는다")
        void applyOutcome_retryable_guardBlocked_noOutboxNoEvent() {
            // given — 좀비 회수 경합 등으로 재시도 신호가 도착하기 전에 inbox 가 이미 종결(APPROVED)됨
            inboxRepository.transitToApproved(ORDER_ID, "{}");
            GatewayOutcome outcome = new GatewayOutcome.Retryable("late retry signal");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — 재시도 outbox 행이 생기지 않는다(stray 행 차단)
            assertThat(outboxRepository.findAll()).isEmpty();

            // then — attempt 는 가드에 걸려 증가하지 않고, 상태도 종결 그대로다
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getAttempt()).isEqualTo(1);
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);

            // then — 발행 이벤트도 없다
            verify(eventPublisher, never()).publishEvent(any());
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
        @DisplayName("확정실패_전이_1건반영이면_발행행_저장")
        void applyOutcome_definitiveFailure_transitionApplied_savesOutbox() {
            // given
            GatewayOutcome outcome = new GatewayOutcome.NonRetryable("card_declined");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
            verify(eventPublisher, times(1)).publishEvent(any());
        }

        @Test
        @DisplayName("확정실패_전이_0건반영이면_발행행_미저장")
        void applyOutcome_definitiveFailure_transitionBlocked_noOutboxRow() {
            // given — 좀비 회수 경합 등으로 이미 종결(APPROVED)된 뒤 뒤늦게 확정 실패 응답이 도착
            inboxRepository.transitToApproved(ORDER_ID, "{}");
            GatewayOutcome outcome = new GatewayOutcome.NonRetryable("card_declined");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — 발행 행 자체가 만들어지지 않는다(폴링 안전망 우회 차단)
            assertThat(outboxRepository.findAll()).isEmpty();
            verify(eventPublisher, never()).publishEvent(any());

            // then — inbox 는 기존 종결 상태를 유지한다
            PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
            assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
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
    // 재시도 백오프 회차 — computeBackoff 에 실패한 attempt 를 그대로 넘기는지 검증
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("재시도 백오프 회차")
    class RetryBackoffScheduling {

        @Test
        @DisplayName("첫_재시도_예약은_기준_2초_구간에_들어간다")
        void firstRetryScheduledWithinTwoSecondWindow() {
            // given — 최초 실패(attempt=1)
            GatewayOutcome outcome = new GatewayOutcome.Retryable("network timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 1, NOW);

            // then — base 2s × 3^0, jitter ±25% → [1.5s, 2.5s]
            assertThat(retryDelay()).isBetween(Duration.ofMillis(1_500), Duration.ofMillis(2_500));
        }

        @Test
        @DisplayName("두번째_재시도_예약은_기준_6초_구간에_들어간다")
        void secondRetryScheduledWithinSixSecondWindow() {
            // given — 두번째 실패(attempt=2)
            GatewayOutcome outcome = new GatewayOutcome.Retryable("network timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), 2, NOW);

            // then — base 2s × 3^1, jitter ±25% → [4.5s, 7.5s]
            assertThat(retryDelay()).isBetween(Duration.ofMillis(4_500), Duration.ofMillis(7_500));
        }

        @Test
        @DisplayName("마지막_재시도_예약은_기준_18초_구간에_들어가_좀비_회수_타임아웃보다_짧다")
        void lastRetryScheduledWithinEighteenSecondWindow_shorterThanZombieRecoveryTimeout() {
            // given — 마지막으로 재시도가 예약되는 실패(attempt=MAX_ATTEMPTS-1) — 그 다음 실패(attempt=MAX_ATTEMPTS)는 DLQ 경로로 빠진다
            GatewayOutcome outcome = new GatewayOutcome.Retryable("network timeout");

            // when
            sut.applyOutcome(outcome, buildRequest(ORDER_ID), RetryPolicy.MAX_ATTEMPTS - 1, NOW);

            // then — base 2s × 3^2, jitter ±25% → [13.5s, 22.5s]
            Duration delay = retryDelay();
            assertThat(delay).isBetween(Duration.ofMillis(13_500), Duration.ofMillis(22_500));
            // pg-service application.yml 의 inbox-polling-worker.in-progress-timeout-ms(60s, 좀비 회수 타임아웃)보다 짧아야
            // 예약된 재시도가 좀비 폴러보다 먼저 수행된다.
            assertThat(delay).isLessThan(Duration.ofMillis(60_000));
        }

        private Duration retryDelay() {
            List<PgOutbox> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            return Duration.between(NOW, rows.get(0).getAvailableAt());
        }
    }

    // -----------------------------------------------------------------------
    // self-loop 누적 시뮬 — PgInboxProcessor.resolveAttempt + PgVendorCallService 결합
    // attempt 1→2→3→4 결정적 단정 (단위 Fake, 동시성 없음). 좀비 경로(processInProgressZombie) 1건 포함.
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("self-loop 누적 시뮬")
    class SelfLoopAccumulation {

        private static final Long INBOX_ID = 1L;

        private PgInboxProcessor processor;

        @BeforeEach
        void setUpProcessor() {
            // PgInboxProcessor 는 invokeVendor/applyOutcome 을 호출하는 워커 — 같은 Fake/sut 재사용
            processor = new PgInboxProcessor(inboxRepository, sut, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("동일 orderId 4회 self-loop — attempt 1→2→3 재시도(누적 증가) → 4 DLQ 도달, applyOutcome 전달값 1,2,3,4 순")
        void selfLoop_fourAttempts_accumulatesThenReachesDlq() {
            // attempt=1 (최초 IN_PROGRESS, setUp 에서 적재) — processPending 호출 시 transitPendingToInProgress 가
            // PENDING 이 아니므로 false 가 되어 워커 경로를 못 타니, 좀비 회수 경로(processInProgressZombie)로 진입.
            // throwOnConfirm 은 1회용 — self-loop 매 회마다 재주입한다.

            // 1회차 — attempt=1 → Retryable → 재시도(누적 attempt=2), DLQ 0건
            gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));
            processor.processInProgressZombie(INBOX_ID);
            assertThat(inboxRepository.findByOrderId(ORDER_ID).orElseThrow().getAttempt()).isEqualTo(2);
            assertThat(outboxRepository.findAll()).filteredOn(o -> o.getTopic().equals(PgTopics.COMMANDS_CONFIRM_DLQ))
                    .isEmpty();

            // 2회차 — attempt=2 → Retryable → 재시도(누적 attempt=3)
            gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));
            processor.processInProgressZombie(INBOX_ID);
            assertThat(inboxRepository.findByOrderId(ORDER_ID).orElseThrow().getAttempt()).isEqualTo(3);

            // 3회차 — attempt=3 → Retryable → 재시도(누적 attempt=4, 아직 MAX 미도달 — shouldRetry(3)=true)
            gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));
            processor.processInProgressZombie(INBOX_ID);
            assertThat(inboxRepository.findByOrderId(ORDER_ID).orElseThrow().getAttempt()).isEqualTo(4);

            // 4회차 — attempt=4(MAX) → Retryable → DLQ 도달, attempt 더 이상 증가하지 않음
            gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));
            processor.processInProgressZombie(INBOX_ID);
            assertThat(inboxRepository.findByOrderId(ORDER_ID).orElseThrow().getAttempt()).isEqualTo(4);

            List<PgOutbox> dlqRows = outboxRepository.findAll().stream()
                    .filter(o -> o.getTopic().equals(PgTopics.COMMANDS_CONFIRM_DLQ))
                    .toList();
            assertThat(dlqRows).hasSize(1);

            List<PgOutbox> retryRows = outboxRepository.findAll().stream()
                    .filter(o -> o.getTopic().equals(PgTopics.COMMANDS_CONFIRM))
                    .toList();
            assertThat(retryRows).hasSize(3);

            // inbox 는 DLQ 분기에서도 IN_PROGRESS 유지 (QUARANTINED 전이는 PgDlqService 책임)
            assertThat(inboxRepository.findByOrderId(ORDER_ID).orElseThrow().getStatus())
                    .isEqualTo(PgInboxStatus.IN_PROGRESS);
        }
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    private PgConfirmRequest buildRequest(String orderId) {
        return new PgConfirmRequest(orderId, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS);
    }
}
