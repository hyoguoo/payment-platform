package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 의존성 가용성 게이지 — DB·redis-dedupe·redis-stock 폴링 상태를 Prometheus gauge 로 노출한다.
 *
 * <ul>
 *   <li>{@code dependency_up{component="db|redis-dedupe|redis-stock"}} — 1=UP / 0=그 외
 *   <li>{@code dependency_health_last_poll_timestamp_seconds} — 폴러 한 바퀴 완료 시각 (epoch seconds)
 * </ul>
 *
 * <p>각 컴포넌트 조회는 {@code metrics.payment.dependency.timeout-seconds} 타임아웃 가드로 감싸
 * Hikari connectionTimeout(30s) 등 블로킹 경로로 폴러가 직렬화되는 것을 방지한다.
 * 타임아웃 시 해당 컴포넌트는 0(DOWN)으로 처리된다.
 *
 * <p>payment redis 는 redis-dedupe(체크아웃 멱등, 6379)와 redis-stock(선차감, 6380)으로
 * 분리 노출한다 — composite 로 묶으면 단일 컴포넌트 장애가 시리즈 부재로 이어지는 dead branch 방지.
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
    static final String COMPONENT_REDIS_DEDUPE = "redis-dedupe";
    static final String COMPONENT_REDIS_STOCK = "redis-stock";

    private static final String TAG_COMPONENT = "component";
    private static final long GAUGE_DOWN = 0L;
    private static final long GAUGE_UP = 1L;

    private final DataSource dataSource;
    private final RedisConnectionFactory dedupeRedisConnectionFactory;
    private final RedisConnectionFactory stockRedisConnectionFactory;
    private final Clock clock;
    private final long timeoutSeconds;
    private final ExecutorService healthCheckExecutor;

    private final AtomicLong dbGauge = new AtomicLong(GAUGE_DOWN);
    private final AtomicLong redisDedupe = new AtomicLong(GAUGE_DOWN);
    private final AtomicLong redisStock = new AtomicLong(GAUGE_DOWN);
    private final AtomicLong lastPollTimestamp = new AtomicLong(0);

    public DependencyHealthMetrics(
            MeterRegistry meterRegistry,
            DataSource dataSource,
            @Qualifier("redisConnectionFactory") RedisConnectionFactory dedupeRedisConnectionFactory,
            @Qualifier("stockCacheRedisConnectionFactory") RedisConnectionFactory stockRedisConnectionFactory,
            Clock clock,
            @Value("${metrics.payment.dependency.timeout-seconds:2}") long timeoutSeconds) {
        this.dataSource = dataSource;
        this.dedupeRedisConnectionFactory = dedupeRedisConnectionFactory;
        this.stockRedisConnectionFactory = stockRedisConnectionFactory;
        this.clock = clock;
        this.timeoutSeconds = timeoutSeconds;
        this.healthCheckExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Gauge.builder(METRIC_DEPENDENCY_UP, dbGauge, AtomicLong::doubleValue)
                .description("DB 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_DB)
                .register(meterRegistry);

        Gauge.builder(METRIC_DEPENDENCY_UP, redisDedupe, AtomicLong::doubleValue)
                .description("redis-dedupe(체크아웃 멱등) 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_REDIS_DEDUPE)
                .register(meterRegistry);

        Gauge.builder(METRIC_DEPENDENCY_UP, redisStock, AtomicLong::doubleValue)
                .description("redis-stock(선차감) 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_REDIS_STOCK)
                .register(meterRegistry);

        Gauge.builder(METRIC_LAST_POLL_TIMESTAMP, lastPollTimestamp, AtomicLong::doubleValue)
                .description("의존성 가용성 폴러 마지막 완료 시각 (epoch seconds) — staleness 탐지용")
                .register(meterRegistry);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.METRICS_INIT,
                () -> "component=DependencyHealthMetrics timeoutSeconds=" + timeoutSeconds);
    }

    /**
     * 의존성 가용성을 폴링하고 게이지를 갱신한다.
     *
     * <p>각 컴포넌트 조회는 독립적으로 타임아웃 가드가 적용되며,
     * 모든 컴포넌트 조회 완료 후 {@code dependency_health_last_poll_timestamp_seconds} 를 갱신한다.
     * 타임아웃·조회 실패 모두 해당 컴포넌트를 0(DOWN) 으로 기록한다.
     */
    @Scheduled(fixedDelayString = "${metrics.payment.dependency.polling-interval-seconds:10}000")
    public void poll() {
        dbGauge.set(checkWithTimeout(this::checkDbHealth));
        redisDedupe.set(checkWithTimeout(this::checkRedisDedupeHealth));
        redisStock.set(checkWithTimeout(this::checkRedisStockHealth));
        lastPollTimestamp.set(clock.instant().getEpochSecond());

        LogFmt.debug(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_UPDATED,
                () -> "db=" + dbGauge.get()
                        + " redis-dedupe=" + redisDedupe.get()
                        + " redis-stock=" + redisStock.get());
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
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_UPDATED,
                    () -> "dependency health check timed out after " + timeoutSeconds + "s → DOWN");
            return GAUGE_DOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GAUGE_DOWN;
        } catch (ExecutionException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_UPDATED,
                    () -> "dependency health check failed cause=" + e.getCause());
            return GAUGE_DOWN;
        }
    }

    private boolean checkDbHealth() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1);
        }
    }

    private boolean checkRedisDedupeHealth() {
        try (RedisConnection conn = dedupeRedisConnectionFactory.getConnection()) {
            conn.ping();
            return true;
        }
    }

    private boolean checkRedisStockHealth() {
        try (RedisConnection conn = stockRedisConnectionFactory.getConnection()) {
            conn.ping();
            return true;
        }
    }
}
