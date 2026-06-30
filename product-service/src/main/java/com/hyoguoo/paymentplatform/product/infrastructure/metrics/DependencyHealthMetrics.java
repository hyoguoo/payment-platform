package com.hyoguoo.paymentplatform.product.infrastructure.metrics;

import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 의존성 가용성 게이지 — DB 폴링 상태를 Prometheus gauge 로 노출한다.
 *
 * <ul>
 *   <li>{@code dependency_up{component="db"}} — 1=UP / 0=그 외
 *   <li>{@code dependency_health_last_poll_timestamp_seconds} — 폴러 한 바퀴 완료 시각 (epoch seconds)
 * </ul>
 *
 * <p>DB 조회는 {@code metrics.product.dependency.timeout-seconds} 타임아웃 가드로 감싸
 * Hikari connectionTimeout(30s) 등 블로킹 경로로 폴러가 직렬화되는 것을 방지한다.
 * 타임아웃 시 해당 컴포넌트는 0(DOWN)으로 처리된다.
 *
 * <p>staleness 2차 방어: {@code dependency_health_last_poll_timestamp_seconds} 가 갱신되지 않으면
 * 알람 규칙 {@code time() - <gauge> > N} 으로 폴러 블로킹 / 스레드 사망을 탐지한다.
 */
@Slf4j
@Component
public class DependencyHealthMetrics {

    public static final String METRIC_DEPENDENCY_UP = "dependency_up";
    public static final String METRIC_LAST_POLL_TIMESTAMP = "dependency_health_last_poll_timestamp_seconds";
    static final String COMPONENT_DB = "db";

    private static final String TAG_COMPONENT = "component";
    private static final long GAUGE_DOWN = 0L;
    private static final long GAUGE_UP = 1L;

    private final DataSource dataSource;
    private final Clock clock;
    private final long timeoutSeconds;
    private final ExecutorService healthCheckExecutor;

    private final AtomicLong dbGauge = new AtomicLong(GAUGE_DOWN);
    private final AtomicLong lastPollTimestamp = new AtomicLong(0);

    public DependencyHealthMetrics(
            MeterRegistry meterRegistry,
            DataSource dataSource,
            Clock clock,
            @Value("${metrics.product.dependency.timeout-seconds:2}") long timeoutSeconds) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.timeoutSeconds = timeoutSeconds;
        this.healthCheckExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Gauge.builder(METRIC_DEPENDENCY_UP, dbGauge, AtomicLong::doubleValue)
                .description("DB 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_DB)
                .register(meterRegistry);

        Gauge.builder(METRIC_LAST_POLL_TIMESTAMP, lastPollTimestamp, AtomicLong::doubleValue)
                .description("의존성 가용성 폴러 마지막 완료 시각 (epoch seconds) — staleness 탐지용")
                .register(meterRegistry);

        LogFmt.info(log, LogDomain.PRODUCT, EventType.METRICS_INIT,
                () -> "component=DependencyHealthMetrics timeoutSeconds=" + timeoutSeconds);
    }

    /**
     * 의존성 가용성을 폴링하고 게이지를 갱신한다.
     *
     * <p>DB 조회는 타임아웃 가드가 적용되며,
     * 조회 완료 후 {@code dependency_health_last_poll_timestamp_seconds} 를 갱신한다.
     * 타임아웃·조회 실패 모두 해당 컴포넌트를 0(DOWN) 으로 기록한다.
     */
    @Scheduled(fixedDelayString = "${metrics.product.dependency.polling-interval-seconds:10}000")
    public void poll() {
        dbGauge.set(checkWithTimeout(this::checkDbHealth));
        lastPollTimestamp.set(clock.instant().getEpochSecond());

        LogFmt.debug(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED,
                () -> "db=" + dbGauge.get());
    }

    private long checkWithTimeout(Callable<Boolean> healthCheck) {
        Future<Boolean> future = healthCheckExecutor.submit(healthCheck);
        return evaluateFuture(future);
    }

    private long evaluateFuture(Future<Boolean> future) {
        try {
            Boolean result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result) ? GAUGE_UP : GAUGE_DOWN;
        } catch (TimeoutException e) {
            future.cancel(true);
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED,
                    () -> "dependency health check timed out after " + timeoutSeconds + "s → DOWN");
            return GAUGE_DOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GAUGE_DOWN;
        } catch (ExecutionException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED,
                    () -> "dependency health check failed cause=" + e.getCause());
            return GAUGE_DOWN;
        }
    }

    private boolean checkDbHealth() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1);
        }
    }
}
