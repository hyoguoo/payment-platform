package com.hyoguoo.paymentplatform.pg.infrastructure.metrics;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
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
 * pg_outbox 관측 지표 — Prometheus gauge.
 *
 * <ul>
 *   <li>{@code pg_outbox.pending_count} — processedAt=null AND availableAt &lt;= now row 수
 *   <li>{@code pg_outbox.future_pending_count} — processedAt=null AND availableAt &gt; now row 수
 *   <li>{@code pg_outbox.oldest_pending_age_seconds} — 가장 오래된 pending row 체류 시간(초)
 * </ul>
 *
 * <p>Gauge는 Supplier 기반으로 등록하되, 내부 캐시 갱신은 {@link #refresh()}가 매분 수행한다.
 *
 * <p><b>제거된 지표</b> — {@code pg_outbox.attempt_count_histogram} (2026-07-28 제거).
 * {@code pg_outbox.attempt} 컬럼이 항상 0 인 죽은 값이라 히스토그램이 정보량 0 이었고, 이를 채우려고
 * {@code findPendingBatch(Integer.MAX_VALUE, now)} 로 pending 행 전량을 매분 메모리에 적재하는 비용만 실재했다.
 * 참조하는 대시보드 없음을 확인 후 제거 — payment-service 의 동명 {@code payment_outbox_attempt_count_histogram}
 * 과는 무관하며 그 지표는 유지된다.
 */
@Slf4j
@Component
public class PgOutboxMetrics {

    public static final String PENDING_COUNT = "pg_outbox.pending_count";
    public static final String FUTURE_PENDING_COUNT = "pg_outbox.future_pending_count";
    public static final String OLDEST_PENDING_AGE_SECONDS = "pg_outbox.oldest_pending_age_seconds";

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
     * 매분 Gauge 캐시 갱신.
     * 1분 단위 재계산은 Prometheus scrape 주기 대비 충분한 freshness 를 제공한다.
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
    }
}
