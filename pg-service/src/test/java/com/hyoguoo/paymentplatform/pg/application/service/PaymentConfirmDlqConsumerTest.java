package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer.PaymentConfirmConsumer;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer.PaymentConfirmDlqConsumer;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PaymentConfirmDlqConsumer(PgDlqService) 단위 테스트.
 * DLQ 전용 consumer 분리 + terminal 중복 흡수 동작을 검증한다.
 * domain_risk=true — QUARANTINED 전이 원자성 + no-op 중복 방어 시나리오 커버.
 */
@DisplayName("PaymentConfirmDlqConsumer(PgDlqService)")
class PaymentConfirmDlqConsumerTest {

    private static final String ORDER_ID = "order-dlq-001";
    private static final String PAYMENT_KEY = "pk-dlq-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);
    private static final String EVENT_UUID = "evt-dlq-uuid-001";

    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private PgDlqService pgDlqService;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        pgDlqService = new PgDlqService(inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()));
    }

    // -----------------------------------------------------------------------
    // TC1: DLQ 메시지 정상 처리 → pg_inbox QUARANTINED + payment.events.confirmed outbox row 1건
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dlq_consumer — DLQ 메시지 정상 처리 시 pg_inbox QUARANTINED 전이 + payment.events.confirmed outbox row 1건 (불변식 6)")
    void dlq_consumer_WhenNormalMessage_ShouldQuarantine() {
        // given — inbox가 IN_PROGRESS 상태 (DLQ 진입 직전 상태)
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        pgDlqService.handle(command);

        // then — inbox QUARANTINED 전이
        PgInbox updated = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(updated.getReasonCode()).isEqualTo("RETRY_EXHAUSTED");

        // then — pg_outbox에 payment.events.confirmed row 1건 INSERT
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox outboxRow = outboxRows.get(0);
        assertThat(outboxRow.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(outboxRow.getPayload()).containsIgnoringCase("QUARANTINED");
        assertThat(outboxRow.getPayload()).containsIgnoringCase("RETRY_EXHAUSTED");
    }

    // -----------------------------------------------------------------------
    // TC2: pg_inbox가 이미 terminal → no-op (불변식 6c)
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("dlq_consumer — pg_inbox가 이미 terminal(APPROVED/FAILED/QUARANTINED)이면 no-op (불변식 6c), pg_outbox row 0건")
    void dlq_consumer_WhenAlreadyTerminal_ShouldBeNoOp(PgInboxStatus terminalStatus) {
        // given — inbox가 이미 terminal 상태
        PgInbox terminalInbox = PgInbox.of(
                ORDER_ID, terminalStatus, AMOUNT.longValue(),
                "{\"status\":\"" + terminalStatus + "\"}", "SOME_REASON",
                Instant.now(), Instant.now());
        inboxRepository.save(terminalInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        pgDlqService.handle(command);

        // then — inbox 상태 변경 없음
        PgInbox unchanged = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(terminalStatus);

        // then — pg_outbox row 0건 (no-op)
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // TC3: QUARANTINED 전이 시 payment.events.confirmed outbox row 1건만 INSERT (보상 큐 row 없음)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dlq_consumer — QUARANTINED 전이 시 payment.events.confirmed outbox row 1건만 INSERT, 보상 큐 row 없음")
    void dlq_consumer_WhenQuarantined_ShouldInsertSingleConfirmedRow() {
        // given — inbox IN_PROGRESS
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        pgDlqService.handle(command);

        // then — pg_outbox row 정확히 1건
        List<PgOutbox> allRows = outboxRepository.findAll();
        assertThat(allRows).hasSize(1);

        // then — 해당 row는 payment.events.confirmed 토픽 (보상 큐 row 없음)
        assertThat(allRows.get(0).getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        long compensationRows = allRows.stream()
                .filter(r -> !PgTopics.EVENTS_CONFIRMED.equals(r.getTopic()))
                .count();
        assertThat(compensationRows).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // TC4: PaymentConfirmDlqConsumer 는 PaymentConfirmConsumer 와 다른 bean
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("dlq_consumer — PaymentConfirmDlqConsumer 는 PaymentConfirmConsumer 와 물리적으로 다른 클래스")
    void dlq_consumer_WhenConsumerItself_ShouldBeDifferentBeanFromNormalConsumer() {
        // DLQ consumer 는 PaymentConfirmConsumer 와 별도 Spring bean (groupId 분리, 다른 클래스)
        assertThat(PaymentConfirmDlqConsumer.class)
                .isNotEqualTo(PaymentConfirmConsumer.class);

        // 각 클래스가 독립적으로 선언된 최상위 클래스임을 확인
        assertThat(PaymentConfirmDlqConsumer.class.getDeclaringClass()).isNull();
        assertThat(PaymentConfirmConsumer.class.getDeclaringClass()).isNull();
    }
}
