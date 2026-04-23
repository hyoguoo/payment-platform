package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgVendorCallService 단위 테스트.
 * ADR-30: 재시도 = outbox available_at 지연 표현.
 * domain_risk=true: 성공/재시도/DLQ/확정실패 + 원자성 불변식 6 검증.
 */
@DisplayName("PgVendorCallService")
class PgVendorCallServiceTest {

    private static final String ORDER_ID = "order-vendor-001";
    private static final String PAYMENT_KEY = "pk-vendor-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(15000);
    private static final String EVENT_UUID = "evt-vendor-uuid-001";

    private static final Instant NOW = Instant.parse("2026-04-21T00:00:00Z");

    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private FakePgGatewayAdapter gatewayAdapter;
    private ApplicationEventPublisher eventPublisher;
    private PgVendorCallService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        gatewayAdapter = new FakePgGatewayAdapter();
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        sut = new PgVendorCallService(inboxRepository, outboxRepository, gatewayAdapter, eventPublisher,
                new ConfirmedEventPayloadSerializer(objectMapper), objectMapper);

        // inbox를 IN_PROGRESS 상태로 사전 준비 (callVendor 진입 전제조건)
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, NOW, NOW);
        inboxRepository.save(inbox);
    }

    // -----------------------------------------------------------------------
    // TC1: 성공 경로 — APPROVED outbox row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — 벤더 호출 성공 시 payment.events.confirmed + status=APPROVED outbox row 1건 INSERT")
    void callVendor_WhenSuccess_ShouldInsertApprovedOutboxRow() {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null);
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        // when
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, NOW);

        // then — outbox 1건: topic=payment.events.confirmed, payload에 APPROVED 포함
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        PgOutbox row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(row.getPayload()).containsIgnoringCase("APPROVED");

        // then — inbox 상태 APPROVED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // TC2: 재시도 경로 — attempt=1에서 retryable 오류 → retry outbox row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — retryable 오류 + attempt=1 → payment.commands.confirm + available_at > now + attempt=2 header")
    void callVendor_WhenRetryableErrorAndAttemptNotExceeded_ShouldInsertRetryOutboxRow() {
        // given — retryable 예외 주입
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("network timeout"));

        Instant now = NOW;

        // when — attempt=1
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, now);

        // then — outbox 1건: topic=payment.commands.confirm
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        PgOutbox row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM);

        // then — available_at > now (지연 표현)
        assertThat(row.getAvailableAt()).isAfter(now);

        // then — headers에 attempt=2 포함
        assertThat(row.getHeadersJson()).contains("\"attempt\":2");

        // then — inbox 상태는 IN_PROGRESS 유지 (재시도 중)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    // -----------------------------------------------------------------------
    // TC3: DLQ 경로 — attempt=4에서 retryable 오류 → DLQ outbox row (불변식 6)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — retryable 오류 + attempt=4 → payment.commands.confirm.dlq + attempt=4 header (불변식 6)")
    void callVendor_WhenRetryableErrorAndAttemptExceeded_ShouldInsertDlqOutboxRow() {
        // given — attempt=4, retryable 예외
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("upstream timeout"));

        Instant now = NOW;

        // when — attempt=4 (MAX)
        sut.callVendor(buildRequest(ORDER_ID, 4), 4, now);

        // then — outbox 1건: topic=payment.commands.confirm.dlq
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        PgOutbox row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(PgTopics.COMMANDS_CONFIRM_DLQ);

        // then — headers에 attempt=4 포함 (불변식 6)
        assertThat(row.getHeadersJson()).contains("\"attempt\":4");
    }

    // -----------------------------------------------------------------------
    // TC4: 확정 실패 경로 — FAILED outbox row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — non-retryable 확정 실패 시 payment.events.confirmed + status=FAILED outbox row 1건 INSERT")
    void callVendor_WhenDefinitiveFailure_ShouldInsertFailedOutboxRow() {
        // given — non-retryable 예외 (카드 거절 등)
        gatewayAdapter.throwOnConfirm(PgGatewayNonRetryableException.of("card_declined"));

        Instant now = NOW;

        // when
        sut.callVendor(buildRequest(ORDER_ID, 1), 1, now);

        // then — outbox 1건: topic=payment.events.confirmed, payload에 FAILED 포함
        List<PgOutbox> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        PgOutbox row = rows.get(0);
        assertThat(row.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(row.getPayload()).containsIgnoringCase("FAILED");

        // then — inbox 상태 FAILED 전이
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
    }

    // -----------------------------------------------------------------------
    // TC5: DLQ 원자성 — DLQ row INSERT와 inbox 상태 기록이 같은 TX (불변식 6)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("retry — attempt 소진 경계에서 DLQ outbox row INSERT와 pg_inbox 상태 기록 원자성 검증")
    void retry_WhenAttemptExceeded_ShouldWriteDlqOutboxRow() {
        // given — attempt=MAX(4), retryable 예외
        gatewayAdapter.throwOnConfirm(PgGatewayRetryableException.of("max attempt reached"));

        Instant now = NOW;

        // when
        sut.callVendor(buildRequest(ORDER_ID, RetryPolicy.MAX_ATTEMPTS), RetryPolicy.MAX_ATTEMPTS, now);

        // then — DLQ row 정확히 1건 (원자성: 이중 INSERT 없음)
        List<PgOutbox> rows = outboxRepository.findAll();
        long dlqCount = rows.stream()
                .filter(r -> PgTopics.COMMANDS_CONFIRM_DLQ.equals(r.getTopic()))
                .count();
        assertThat(dlqCount).isEqualTo(1);

        // then — DLQ row에 attempt=MAX_ATTEMPTS header
        PgOutbox dlqRow = rows.stream()
                .filter(r -> PgTopics.COMMANDS_CONFIRM_DLQ.equals(r.getTopic()))
                .findFirst()
                .orElseThrow();
        assertThat(dlqRow.getHeadersJson()).contains("\"attempt\":" + RetryPolicy.MAX_ATTEMPTS);

        // then — inbox는 IN_PROGRESS 유지 (QUARANTINED는 T2b-02 DLQ consumer가 전이)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    // -----------------------------------------------------------------------
    // 헬퍼
    // -----------------------------------------------------------------------

    private PgConfirmRequest buildRequest(String orderId, int attempt) {
        return new PgConfirmRequest(orderId, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS);
    }
}
