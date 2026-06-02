package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentOutboxMetrics 테스트")
class PaymentOutboxMetricsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private FakePaymentOutboxRepositoryForMetrics fakeRepo;
    private PaymentOutboxMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeRepo = new FakePaymentOutboxRepositoryForMetrics();
        metrics = new PaymentOutboxMetrics(fakeRepo, FIXED_CLOCK, meterRegistry);
    }

    @Test
    @DisplayName("refresh - PENDING row 2건이 있으면 pending_count Gauge가 2를 반환한다")
    void refresh_WithTwoPendingRows_GaugeReturnsTwoForPendingCount() {
        // given
        fakeRepo.addOutbox(pendingOutbox("order-001", null, FIXED_INSTANT.minus(Duration.ofSeconds(60))));
        fakeRepo.addOutbox(pendingOutbox("order-002", null, FIXED_INSTANT.minus(Duration.ofSeconds(120))));

        // when
        metrics.refresh();

        // then
        Gauge gauge = meterRegistry.find(PaymentOutboxMetrics.PENDING_COUNT).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("refresh - 미래 nextRetryAt 가 있는 PENDING row 는 future_pending_count 에 반영된다")
    void refresh_WithFuturePendingRow_GaugeReflectsFuturePendingCount() {
        // given
        Instant futureRetryAt = FIXED_INSTANT.plus(Duration.ofMinutes(5));
        fakeRepo.addOutbox(pendingOutbox("order-001", futureRetryAt, FIXED_INSTANT.minus(Duration.ofSeconds(30))));
        fakeRepo.addOutbox(pendingOutbox("order-002", null, FIXED_INSTANT.minus(Duration.ofSeconds(60))));

        // when
        metrics.refresh();

        // then: future_pending_count = 1 (nextRetryAt > now 인 것만)
        Gauge futureGauge = meterRegistry.find(PaymentOutboxMetrics.FUTURE_PENDING_COUNT).gauge();
        assertThat(futureGauge).isNotNull();
        assertThat(futureGauge.value()).isEqualTo(1.0);

        // pending_count = 2 (PENDING 전체)
        Gauge pendingGauge = meterRegistry.find(PaymentOutboxMetrics.PENDING_COUNT).gauge();
        assertThat(pendingGauge).isNotNull();
        assertThat(pendingGauge.value()).isEqualTo(2.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private PaymentOutbox pendingOutbox(String orderId, Instant nextRetryAt, Instant createdAt) {
        return PaymentOutbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(nextRetryAt)
                .inFlightAt(null)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .allArgsBuild();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Fake 구현
    // ──────────────────────────────────────────────────────────────────────────

    static class FakePaymentOutboxRepositoryForMetrics
            implements com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository {

        private final List<PaymentOutbox> store = new ArrayList<>();

        void addOutbox(PaymentOutbox outbox) {
            store.add(outbox);
        }

        @Override
        public PaymentOutbox save(PaymentOutbox paymentOutbox) {
            return paymentOutbox;
        }

        @Override
        public Optional<PaymentOutbox> findByOrderId(String orderId) {
            return store.stream().filter(o -> o.getOrderId().equals(orderId)).findFirst();
        }

        @Override
        public List<PaymentOutbox> findPendingBatch(int limit) {
            return store.stream()
                    .filter(o -> o.getStatus() == PaymentOutboxStatus.PENDING)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<PaymentOutbox> findTimedOutInFlight(Instant before) {
            return Collections.emptyList();
        }

        @Override
        public boolean claimToInFlight(String orderId, Instant inFlightAt) {
            return false;
        }

        @Override
        public long countPending() {
            return store.stream().filter(o -> o.getStatus() == PaymentOutboxStatus.PENDING).count();
        }

        @Override
        public long countFuturePending(Instant now) {
            return store.stream()
                    .filter(o -> o.getStatus() == PaymentOutboxStatus.PENDING
                            && o.getNextRetryAt() != null
                            && o.getNextRetryAt().isAfter(now))
                    .count();
        }

        @Override
        public Optional<Instant> findOldestPendingCreatedAt() {
            return store.stream()
                    .filter(o -> o.getStatus() == PaymentOutboxStatus.PENDING)
                    .map(PaymentOutbox::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(Instant::compareTo);
        }
    }
}
