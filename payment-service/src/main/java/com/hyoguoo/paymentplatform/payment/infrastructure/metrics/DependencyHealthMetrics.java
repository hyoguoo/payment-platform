package com.hyoguoo.paymentplatform.payment.infrastructure.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 의존성 가용성 게이지 — DB·redis-dedupe·redis-stock 폴링 상태를 Prometheus gauge 로 노출한다.
 *
 * <ul>
 *   <li>{@code dependency_up{component="db|redis-dedupe|redis-stock"}} — 1=UP / 0=그 외
 *   <li>{@code dependency_health_last_poll_timestamp_seconds} — 폴러 마지막 완료 시각 (epoch seconds)
 * </ul>
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

    private final DataSource dataSource;
    private final RedisConnectionFactory dedupeRedisConnectionFactory;
    private final RedisConnectionFactory stockRedisConnectionFactory;
    private final Clock clock;
    private final long timeoutSeconds;

    private final AtomicLong dbGauge = new AtomicLong(0);
    private final AtomicLong redisDedupe = new AtomicLong(0);
    private final AtomicLong redisStock = new AtomicLong(0);
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

        Gauge.builder(METRIC_DEPENDENCY_UP, dbGauge, AtomicLong::doubleValue)
                .description("DB 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_DB)
                .register(meterRegistry);

        Gauge.builder(METRIC_DEPENDENCY_UP, redisDedupe, AtomicLong::doubleValue)
                .description("Redis dedupe 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_REDIS_DEDUPE)
                .register(meterRegistry);

        Gauge.builder(METRIC_DEPENDENCY_UP, redisStock, AtomicLong::doubleValue)
                .description("Redis stock 가용성 (1=UP, 0=그 외)")
                .tag(TAG_COMPONENT, COMPONENT_REDIS_STOCK)
                .register(meterRegistry);

        Gauge.builder(METRIC_LAST_POLL_TIMESTAMP, lastPollTimestamp, AtomicLong::doubleValue)
                .description("의존성 가용성 폴러 마지막 완료 시각 (epoch seconds)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${metrics.payment.dependency.polling-interval-seconds:10}000")
    public void poll() {
        // 스켈레톤: GREEN 단계에서 구현
    }
}
