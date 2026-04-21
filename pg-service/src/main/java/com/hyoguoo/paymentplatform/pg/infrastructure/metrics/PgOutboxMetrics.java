package com.hyoguoo.paymentplatform.pg.infrastructure.metrics;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * pg_outbox 관측 지표 (ADR-31, T2d-02).
 *
 * <ul>
 *   <li>{@code pg_outbox.pending_count} — processedAt=null AND availableAt &lt;= now row 수
 *   <li>{@code pg_outbox.future_pending_count} — processedAt=null AND availableAt &gt; now row 수
 *   <li>{@code pg_outbox.oldest_pending_age_seconds} — 가장 오래된 pending row 체류 시간(초)
 *   <li>{@code pg_outbox.attempt_count_histogram} — attempt 분포 histogram
 * </ul>
 *
 * <p>Gauge는 Supplier 기반으로 등록하되, 내부 캐시 갱신은 {@link #refresh()}가 매분 수행한다.
 */
@Slf4j
@Component
public class PgOutboxMetrics {

    public static final String PENDING_COUNT = "pg_outbox.pending_count";
    public static final String FUTURE_PENDING_COUNT = "pg_outbox.future_pending_count";
    public static final String OLDEST_PENDING_AGE_SECONDS = "pg_outbox.oldest_pending_age_seconds";
    public static final String ATTEMPT_COUNT_HISTOGRAM = "pg_outbox.attempt_count_histogram";

    private final PgOutboxRepository pgOutboxRepository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    private final AtomicLong pendingCount = new AtomicLong(0L);
    private final AtomicLong futurePendingCount = new AtomicLong(0L);
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong(0L);

    public PgOutboxMetrics(
            PgOutboxRepository pgOutboxRepository,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.pgOutboxRepository = pgOutboxRepository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;

        Gauge.builder(PENDING_COUNT, pendingCount, AtomicLong::doubleValue)
                .description("processedAt=null AND availableAt<=now 인 pg_outbox row 수")
                .register(meterRegistry);

        Gauge.builder(FUTURE_PENDING_COUNT, futurePendingCount, AtomicLong::doubleValue)
                .description("processedAt=null AND availableAt>now 인 미래 예약 pg_outbox row 수")
                .register(meterRegistry);

        Gauge.builder(OLDEST_PENDING_AGE_SECONDS, oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("가장 오래된 pending pg_outbox row 체류 시간(초)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * 매분 Gauge 캐시 갱신 + attempt histogram 기록.
     * ADR-31: 1분 단위 재계산.
     */
    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        Instant now = clock.instant();

        pendingCount.set(pgOutboxRepository.countPending(now));
        futurePendingCount.set(pgOutboxRepository.countFuturePending(now));

        pgOutboxRepository.findOldestPendingCreatedAt()
                .ifPresentOrElse(
                        oldest -> oldestPendingAgeSeconds.set(ChronoUnit.SECONDS.between(oldest, now)),
                        () -> oldestPendingAgeSeconds.set(0L));

        recordAttemptHistogram(now);
    }

    private void recordAttemptHistogram(Instant now) {
        pgOutboxRepository.findPendingBatch(Integer.MAX_VALUE, now)
                .forEach(outbox -> buildAttemptSummary().record(outbox.getAttempt()));
    }

    private DistributionSummary buildAttemptSummary() {
        return DistributionSummary.builder(ATTEMPT_COUNT_HISTOGRAM)
                .description("pending pg_outbox row 의 attempt 분포")
                .publishPercentileHistogram(true)
                .register(meterRegistry);
    }
}
