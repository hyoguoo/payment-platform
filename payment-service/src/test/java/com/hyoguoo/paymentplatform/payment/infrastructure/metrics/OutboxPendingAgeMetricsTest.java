package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxPendingAgeMetrics 테스트")
class OutboxPendingAgeMetricsTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 4, 21, 12, 0, 0);

    private SimpleMeterRegistry meterRegistry;
    private FakeOutboxRepository fakeOutboxRepository;
    private OutboxPendingAgeMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeOutboxRepository = new FakeOutboxRepository();
        metrics = new OutboxPendingAgeMetrics(meterRegistry, fakeOutboxRepository, () -> FIXED_NOW);
    }

    @Test
    @DisplayName("record - PENDING 레코드가 있으면 각 레코드의 체류 시간을 histogram에 기록한다")
    void record_ShouldEmitHistogramForEachPendingRecord() {
        // given: 두 PENDING 레코드 — 60초, 120초 체류
        PaymentOutbox outbox1 = pendingOutboxWithCreatedAt("order-001", FIXED_NOW.minusSeconds(60));
        PaymentOutbox outbox2 = pendingOutboxWithCreatedAt("order-002", FIXED_NOW.minusSeconds(120));
        fakeOutboxRepository.addPending(outbox1);
        fakeOutboxRepository.addPending(outbox2);

        // when
        metrics.record();

        // then: histogram에 2건 기록
        DistributionSummary summary = meterRegistry.find("payment.outbox.pending_age_seconds").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2L);
        // 각 값이 기록됨 (60, 120 초)
        assertThat(summary.totalAmount()).isEqualTo(180.0);
    }

    @Test
    @DisplayName("record - PENDING 레코드가 없으면 histogram을 기록하지 않는다")
    void record_ZeroPendingRecords_ShouldNotRecord() {
        // given: 빈 저장소

        // when
        metrics.record();

        // then: histogram이 등록되지 않거나 count가 0
        DistributionSummary summary = meterRegistry.find("payment.outbox.pending_age_seconds").summary();
        if (summary != null) {
            assertThat(summary.count()).isEqualTo(0L);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private PaymentOutbox pendingOutboxWithCreatedAt(String orderId, LocalDateTime createdAt) {
        return PaymentOutbox.allArgsBuilder()
                .id(null)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .nextRetryAt(null)
                .inFlightAt(null)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .allArgsBuild();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FakeOutboxRepository — inner class
    // ──────────────────────────────────────────────────────────────────────────

    static class FakeOutboxRepository
            implements com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository {

        private final List<PaymentOutbox> pendingList = new ArrayList<>();

        void addPending(PaymentOutbox outbox) {
            pendingList.add(outbox);
        }

        @Override
        public PaymentOutbox save(PaymentOutbox paymentOutbox) {
            return paymentOutbox;
        }

        @Override
        public Optional<PaymentOutbox> findByOrderId(String orderId) {
            return pendingList.stream()
                    .filter(o -> o.getOrderId().equals(orderId))
                    .findFirst();
        }

        @Override
        public List<PaymentOutbox> findPendingBatch(int limit) {
            return Collections.unmodifiableList(
                    pendingList.subList(0, Math.min(limit, pendingList.size()))
            );
        }

        @Override
        public List<PaymentOutbox> findTimedOutInFlight(LocalDateTime before) {
            return Collections.emptyList();
        }

        @Override
        public boolean claimToInFlight(String orderId, LocalDateTime inFlightAt) {
            return false;
        }

        @Override
        public long countPending() {
            return pendingList.stream()
                    .filter(o -> o.getStatus() ==
                            com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus.PENDING)
                    .count();
        }

        @Override
        public long countFuturePending(LocalDateTime now) {
            return pendingList.stream()
                    .filter(o -> o.getStatus() ==
                            com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus.PENDING
                            && o.getNextRetryAt() != null
                            && o.getNextRetryAt().isAfter(now))
                    .count();
        }

        @Override
        public Optional<LocalDateTime> findOldestPendingCreatedAt() {
            return pendingList.stream()
                    .filter(o -> o.getStatus() ==
                            com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus.PENDING)
                    .map(com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(LocalDateTime::compareTo);
        }
    }
}
