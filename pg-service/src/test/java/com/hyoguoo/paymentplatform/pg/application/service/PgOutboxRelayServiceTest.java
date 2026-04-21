package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.mock.FakePgEventPublisher;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgOutboxRelayService 단위 테스트.
 * domain_risk=true: 발행 실패 시 row 미갱신이 핵심 불변 — 명시적으로 검증한다.
 */
@DisplayName("PgOutboxRelayService")
class PgOutboxRelayServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private FakePgOutboxRepository outboxRepository;
    private FakePgEventPublisher publisher;
    private PgOutboxRelayService sut;

    @BeforeEach
    void setUp() {
        outboxRepository = new FakePgOutboxRepository();
        publisher = new FakePgEventPublisher();
        sut = new PgOutboxRelayService(outboxRepository, publisher, FIXED_CLOCK);
    }

    @Test
    @DisplayName("relay — topic 필드에 따라 올바른 토픽으로 발행하고 processed_at 을 갱신한다")
    void relay_PublishesByTopicField_ThenMarksDone() {
        // given
        PgOutbox outbox = PgOutbox.of(
                1L,
                PgTopics.EVENTS_CONFIRMED,
                "order-001",
                "{\"orderId\":\"order-001\"}",
                null,
                FIXED_NOW.minusSeconds(1),  // availableAt < NOW → 즉시 발행 가능
                null,                        // processedAt = null → pending
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);

        // when
        sut.relay(1L);

        // then — 발행 검증
        assertThat(publisher.getPublishedCount()).isEqualTo(1);
        FakePgEventPublisher.EventCapture capture = publisher.getLast().orElseThrow();
        assertThat(capture.topic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(capture.key()).isEqualTo("order-001");

        // then — processed_at 갱신 검증
        PgOutbox saved = outboxRepository.findById(1L).orElseThrow();
        assertThat(saved.getProcessedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    @DisplayName("relay — 발행 실패 시 row 의 processed_at 이 null 로 유지된다 (핵심 불변)")
    void relay_WhenPublishFails_DoesNotMarkDone() {
        // given
        PgOutbox outbox = PgOutbox.of(
                2L,
                PgTopics.COMMANDS_CONFIRM,
                "order-002",
                "{\"orderId\":\"order-002\"}",
                null,
                FIXED_NOW.minusSeconds(1),
                null,
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);
        publisher.setFailOnPublish(true);

        // when + then — 예외가 전파된다
        assertThatThrownBy(() -> sut.relay(2L))
                .isInstanceOf(RuntimeException.class);

        // then — processed_at 이 null 로 유지 (row 미갱신)
        PgOutbox unchanged = outboxRepository.findById(2L).orElseThrow();
        assertThat(unchanged.getProcessedAt()).isNull();
    }

    @Test
    @DisplayName("relay — available_at 이 NOW 이후인 row 는 skip 하고 발행하지 않는다")
    void relay_WhenAvailableAtFuture_ShouldSkip() {
        // given
        Instant futureAvailableAt = FIXED_NOW.plusSeconds(60); // 미래 → skip 대상
        PgOutbox outbox = PgOutbox.of(
                3L,
                PgTopics.EVENTS_CONFIRMED,
                "order-003",
                "{\"orderId\":\"order-003\"}",
                null,
                futureAvailableAt,
                null,
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);

        // when
        sut.relay(3L);

        // then — 발행 없음
        assertThat(publisher.getPublishedCount()).isEqualTo(0);

        // then — processed_at 미갱신
        PgOutbox unchanged = outboxRepository.findById(3L).orElseThrow();
        assertThat(unchanged.getProcessedAt()).isNull();
    }
}
