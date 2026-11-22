package com.hyoguoo.paymentplatform.product.infrastructure.metrics

import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * 의존성 가용성 게이지 — DB 폴링 상태를 Prometheus gauge 로 노출한다.
 *
 * - `dependency_up{component="db"}` — 1=UP / 0=그 외
 * - `dependency_health_last_poll_timestamp_seconds` — 폴러 한 바퀴 완료 시각 (epoch seconds)
 *
 * DB 조회는 `metrics.product.dependency.timeout-seconds` 타임아웃 가드로 감싸
 * Hikari connectionTimeout(30s) 등 블로킹 경로로 폴러가 직렬화되는 것을 방지한다.
 * 타임아웃 시 해당 컴포넌트는 0(DOWN)으로 처리된다.
 *
 * staleness 2차 방어: `dependency_health_last_poll_timestamp_seconds` 가 갱신되지 않으면
 * 알람 규칙 `time() - <gauge> > N` 으로 폴러 블로킹 / 스레드 사망을 탐지한다.
 */
@Component
class DependencyHealthMetrics(
    meterRegistry: MeterRegistry,
    private val dataSource: DataSource,
    private val clock: Clock,
    @Value("\${metrics.product.dependency.timeout-seconds:2}")
    private val timeoutSeconds: Long,
) {
    private val healthCheckExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val dbGauge = AtomicLong(GAUGE_DOWN)
    private val lastPollTimestamp = AtomicLong(0)

    init {
        Gauge
            .builder(METRIC_DEPENDENCY_UP, dbGauge) { it.toDouble() }
            .description("DB 가용성 (1=UP, 0=그 외)")
            .tag(TAG_COMPONENT, COMPONENT_DB)
            .register(meterRegistry)
        Gauge
            .builder(METRIC_LAST_POLL_TIMESTAMP, lastPollTimestamp) { it.toDouble() }
            .description("의존성 가용성 폴러 마지막 완료 시각 (epoch seconds) — staleness 탐지용")
            .register(meterRegistry)
        LogFmt.info(log, LogDomain.PRODUCT, EventType.METRICS_INIT) {
            "component=DependencyHealthMetrics timeoutSeconds=$timeoutSeconds"
        }
    }

    /**
     * 의존성 가용성을 폴링하고 게이지를 갱신한다.
     *
     * DB 조회는 타임아웃 가드가 적용되며,
     * 조회 완료 후 `dependency_health_last_poll_timestamp_seconds` 를 갱신한다.
     * 타임아웃·조회 실패 모두 해당 컴포넌트를 0(DOWN) 으로 기록한다.
     */
    @Scheduled(fixedDelayString = "\${metrics.product.dependency.polling-interval-seconds:10}000")
    fun poll() {
        dbGauge.set(checkWithTimeout { checkDbHealth() })
        lastPollTimestamp.set(clock.instant().epochSecond)
        LogFmt.debug(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED) { "db=${dbGauge.get()}" }
    }

    private fun checkWithTimeout(healthCheck: Callable<Boolean>): Long {
        val future = healthCheckExecutor.submit(healthCheck)
        return evaluateFuture(future)
    }

    // 타임아웃·인터럽트·조회 실패는 모두 DOWN 으로 기록하는 것이 이 함수의 처리다
    @Suppress("SwallowedException")
    private fun evaluateFuture(future: Future<Boolean>): Long =
        try {
            if (future.get(timeoutSeconds, TimeUnit.SECONDS) == true) GAUGE_UP else GAUGE_DOWN
        } catch (e: TimeoutException) {
            future.cancel(true)
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED) {
                "dependency health check timed out after ${timeoutSeconds}s → DOWN"
            }
            GAUGE_DOWN
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            GAUGE_DOWN
        } catch (e: ExecutionException) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.METRICS_GAUGE_UPDATED) {
                "dependency health check failed cause=${e.cause}"
            }
            GAUGE_DOWN
        }

    private fun checkDbHealth(): Boolean = dataSource.connection.use { it.isValid(1) }

    companion object {
        const val METRIC_DEPENDENCY_UP = "dependency_up"
        const val METRIC_LAST_POLL_TIMESTAMP = "dependency_health_last_poll_timestamp_seconds"
        internal const val COMPONENT_DB = "db"
        private const val TAG_COMPONENT = "component"
        private const val GAUGE_DOWN = 0L
        private const val GAUGE_UP = 1L

        private val log = LoggerFactory.getLogger(DependencyHealthMetrics::class.java)
    }
}
