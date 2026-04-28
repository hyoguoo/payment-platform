package com.hyoguoo.paymentplatform.pg.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PgOutboxMetrics 테스트")
class PgOutboxMetricsTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private FakePgOutboxRepository fakeRepo;
    private PgOutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeRepo = new FakePgOutboxRepository();
        metrics = new PgOutboxMetrics(fakeRepo, FIXED_CLOCK, meterRegistry);
    }

    @Test
    @DisplayName("refresh - availableAt<=now 인 pending row 2건이 있으면 pending_count Gauge가 2를 반환한다")
    void refresh_WithTwoCurrentPendingRows_GaugeReturnsTwoForPendingCount() {
        // given: availableAt = now - 10s (현재 처리 가능)
        fakeRepo.save(pgOutboxWithAvailableAt(FIXED_NOW.minusSeconds(10)));
        fakeRepo.save(pgOutboxWithAvailableAt(FIXED_NOW.minusSeconds(30)));

        // when
        metrics.refresh();

        // then
        Gauge gauge = meterRegistry.find(PgOutboxMetrics.PENDING_COUNT).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("refresh - availableAt>now 인 future pending row 는 future_pending_count 에 반영된다")
    void refresh_WithFuturePendingRow_GaugeReflectsFuturePendingCount() {
        // given: availableAt = now + 60s (미래 예약)
        fakeRepo.save(pgOutboxWithAvailableAt(FIXED_NOW.plusSeconds(60)));
        // availableAt = now - 10s (현재 처리 가능)
        fakeRepo.save(pgOutboxWithAvailableAt(FIXED_NOW.minusSeconds(10)));

        // when
        metrics.refresh();

        // then: future_pending_count = 1
        Gauge futureGauge = meterRegistry.find(PgOutboxMetrics.FUTURE_PENDING_COUNT).gauge();
        assertThat(futureGauge).isNotNull();
        assertThat(futureGauge.value()).isEqualTo(1.0);

        // pending_count = 1 (availableAt <= now 만)
        Gauge pendingGauge = meterRegistry.find(PgOutboxMetrics.PENDING_COUNT).gauge();
        assertThat(pendingGauge).isNotNull();
        assertThat(pendingGauge.value()).isEqualTo(1.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private PgOutbox pgOutboxWithAvailableAt(Instant availableAt) {
        return PgOutbox.of(
                null,
                "payment.commands.confirm",
                "order-001",
                "{}",
                null,
                availableAt,
                null,
                0,
                FIXED_NOW.minusSeconds(120));
    }
}
