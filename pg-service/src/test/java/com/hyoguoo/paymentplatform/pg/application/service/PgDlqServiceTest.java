package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.core.common.metrics.PgDlqReachMetrics;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * PgDlqService 단위 테스트 — 격리 도달 metric (Task 2).
 *
 * <p>domain_risk=true: QUARANTINED 전이 성공(non-terminal CAS true) 시 1회,
 * 이미 terminal(CAS false — 중복 DLQ 진입)이면 metric 미증가(멱등)를 검증한다.
 */
@DisplayName("PgDlqService — 격리 도달 metric")
class PgDlqServiceTest {

    private static final String ORDER_ID = "order-dlq-001";
    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private SimpleMeterRegistry meterRegistry;
    private PgDlqReachMetrics dlqReachMetrics;
    private PgDlqService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        meterRegistry = new SimpleMeterRegistry();
        dlqReachMetrics = new PgDlqReachMetrics(meterRegistry);

        ObjectMapper objectMapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);

        sut = new PgDlqService(
                inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(objectMapper), fixedClock, dlqReachMetrics);
    }

    @Test
    @DisplayName("QUARANTINED 전이 성공(non-terminal CAS true) → PgDlqReachMetrics 1 증가")
    void handle_transitionsToQuarantined_incrementsMetric() {
        // given — IN_PROGRESS inbox 존재
        inboxRepository.save(PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, 15000L, null, null, NOW, NOW));
        PgConfirmCommand command = buildCommand(ORDER_ID);

        // when
        sut.handle(command);

        // then — inbox QUARANTINED 전이 + metric 1 증가
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);

        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("이미 terminal(QUARANTINED) — 중복 DLQ 진입 시 CAS false → metric 미증가 (멱등)")
    void handle_alreadyTerminal_doesNotIncrementMetric() {
        // given — 이미 QUARANTINED인 inbox
        PgInbox quarantined = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, 15000L, null, null, NOW, NOW);
        quarantined.markQuarantined(null, "RETRY_EXHAUSTED", NOW);
        inboxRepository.save(quarantined);
        PgConfirmCommand command = buildCommand(ORDER_ID);

        // when — 중복 DLQ 메시지 재처리
        sut.handle(command);

        // then — metric 미증가
        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(0.0);
    }

    private PgConfirmCommand buildCommand(String orderId) {
        return new PgConfirmCommand(
                orderId, "pk-dlq-001", BigDecimal.valueOf(15000), PgVendorType.TOSS, "uuid-dlq-001");
    }
}
