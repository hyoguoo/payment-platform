package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * payment_outbox 관측 지표 (ADR-31, T2d-02).
 *
 * <ul>
 *   <li>{@code payment_outbox.pending_count} — PENDING 상태 row 수
 *   <li>{@code payment_outbox.future_pending_count} — PENDING이지만 nextRetryAt &gt; now (미래 예약)
 *   <li>{@code payment_outbox.oldest_pending_age_seconds} — 가장 오래된 PENDING row 체류 시간(초)
 *   <li>{@code payment_outbox.attempt_count_histogram} — retryCount 분포 histogram
 * </ul>
 *
 * <p>Gauge는 Supplier 기반으로 등록하되, 내부 캐시 갱신은 {@link #refresh()}가 매분 수행한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class PaymentOutboxMetrics {

    public static final String PENDING_COUNT = "payment_outbox.pending_count";
    public static final String FUTURE_PENDING_COUNT = "payment_outbox.future_pending_count";
    public static final String OLDEST_PENDING_AGE_SECONDS = "payment_outbox.oldest_pending_age_seconds";
    public static final String ATTEMPT_COUNT_HISTOGRAM = "payment_outbox.attempt_count_histogram";

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final MeterRegistry meterRegistry;

    private final AtomicLong pendingCount = new AtomicLong(0L);
    private final AtomicLong futurePendingCount = new AtomicLong(0L);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0L);

    public PaymentOutboxMetrics(
            PaymentOutboxRepository paymentOutboxRepository,
            LocalDateTimeProvider localDateTimeProvider,
            MeterRegistry meterRegistry) {
        this.paymentOutboxRepository = paymentOutboxRepository;
        this.localDateTimeProvider = localDateTimeProvider;
        this.meterRegistry = meterRegistry;

        Gauge.builder(PENDING_COUNT, pendingCount, AtomicLong::doubleValue)
                .description("PENDING 상태 payment_outbox row 수")
                .register(meterRegistry);

        Gauge.builder(FUTURE_PENDING_COUNT, futurePendingCount, AtomicLong::doubleValue)
                .description("PENDING이지만 nextRetryAt > now 인 미래 예약 row 수")
                .register(meterRegistry);

        Gauge.builder(OLDEST_PENDING_AGE_SECONDS, oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("가장 오래된 PENDING row 체류 시간(초)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * 매분 Gauge 캐시 갱신 + attempt histogram 기록.
     * ADR-31: 1분 단위 재계산으로 Prometheus scrape 주기(30s) 대비 충분한 freshness.
     */
    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        LocalDateTime now = localDateTimeProvider.now();

        pendingCount.set(paymentOutboxRepository.countPending());
        futurePendingCount.set(paymentOutboxRepository.countFuturePending(now));

        paymentOutboxRepository.findOldestPendingCreatedAt()
                .ifPresentOrElse(
                        oldest -> oldestPendingAgeSeconds.set(ChronoUnit.SECONDS.between(oldest, now)),
                        () -> oldestPendingAgeSeconds.set(0L));

        recordAttemptHistogram();
    }

    private void recordAttemptHistogram() {
        paymentOutboxRepository.findPendingBatch(Integer.MAX_VALUE)
                .forEach(outbox -> buildAttemptSummary().record(outbox.getRetryCount()));
    }

    private DistributionSummary buildAttemptSummary() {
        return DistributionSummary.builder(ATTEMPT_COUNT_HISTOGRAM)
                .description("PENDING payment_outbox row 의 retryCount 분포")
                .publishPercentileHistogram(true)
                .register(meterRegistry);
    }
}
