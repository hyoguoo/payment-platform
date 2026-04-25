package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PENDING Outbox 레코드의 체류 시간을 histogram으로 기록한다 (ADR-20, ADR-31).
 *
 * <p>메트릭명: {@code payment.outbox.pending_age_seconds}
 *
 * <p>{@link #record()} 는 외부에서 명시적으로 호출되는 단일 진입점이다.
 * Scheduler나 Reconciler 순회 지점에서 훅으로 호출하거나,
 * 별도 @Scheduled 진입점에서 주기적으로 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPendingAgeMetrics {

    public static final String METRIC_NAME = "payment.outbox.pending_age_seconds";
    private static final int ALL_PENDING_BATCH_SIZE = Integer.MAX_VALUE;

    private final MeterRegistry meterRegistry;
    private final PaymentOutboxRepository paymentOutboxRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    /**
     * 현재 PENDING 상태인 모든 Outbox 레코드의 체류 시간을 histogram에 기록한다.
     *
     * <p>체류 시간 = 현재 시각 - 레코드 createdAt (초 단위).
     * PENDING 레코드가 없으면 아무것도 기록하지 않는다.
     */
    public void record() {
        List<PaymentOutbox> pendingOutboxes =
                paymentOutboxRepository.findPendingBatch(ALL_PENDING_BATCH_SIZE);

        if (pendingOutboxes.isEmpty()) {
            return;
        }

        LocalDateTime now = localDateTimeProvider.now();
        DistributionSummary summary = buildSummary();

        for (PaymentOutbox outbox : pendingOutboxes) {
            if (outbox.getCreatedAt() == null) {
                continue;
            }
            long ageSeconds = ChronoUnit.SECONDS.between(outbox.getCreatedAt(), now);
            summary.record(Math.max(ageSeconds, 0));
        }
    }

    private DistributionSummary buildSummary() {
        return DistributionSummary.builder(METRIC_NAME)
                .description("PENDING 상태 Outbox 레코드의 체류 시간 분포 (seconds)")
                .baseUnit("seconds")
                .publishPercentileHistogram(true)
                .register(meterRegistry);
    }
}
