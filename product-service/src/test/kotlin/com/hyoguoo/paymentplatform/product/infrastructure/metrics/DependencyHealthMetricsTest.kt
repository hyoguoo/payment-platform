package com.hyoguoo.paymentplatform.product.infrastructure.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource

@DisplayName("DependencyHealthMetrics 단위 테스트")
class DependencyHealthMetricsTest {
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var mockDataSource: DataSource
    private lateinit var metrics: DependencyHealthMetrics

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
        mockDataSource = mockk(relaxed = true)
        setupHealthyDb()
        metrics = DependencyHealthMetrics(meterRegistry, mockDataSource, FIXED_CLOCK, 2L)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: UP → 게이지 1
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("health UP 상태면 게이지 1 — DB 정상이면 db 게이지 1.0")
    fun `health_UP_상태면_게이지1`() {
        // when
        metrics.poll()

        // then
        assertThat(componentGaugeValue(DependencyHealthMetrics.COMPONENT_DB)).isEqualTo(1.0)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: 비UP 시나리오 → 게이지 0
    // ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "health_DOWN_상태면_게이지0 — {0} 시나리오에서 db 게이지 0.0")
    @EnumSource(HealthCheckFailureScenario::class)
    @DisplayName("health DOWN 상태면 게이지 0 — DOWN·OUT_OF_SERVICE·UNKNOWN 시나리오에서 DB 게이지 0.0")
    fun `health_DOWN_상태면_게이지0`(scenario: HealthCheckFailureScenario) {
        // given: 각 시나리오에 맞게 DB 목 재구성
        configureDbForFailureScenario(scenario)

        // when
        metrics.poll()

        // then
        assertThat(componentGaugeValue(DependencyHealthMetrics.COMPONENT_DB)).isEqualTo(0.0)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: 타임아웃 → 게이지 0
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("health 조회 타임아웃이면 게이지 0 — DB 조회가 블로킹 상태에서 타임아웃 가드 동작 시 DB 게이지 0.0")
    fun `health조회_타임아웃이면_게이지0`() {
        // given: DB 조회를 영구 블로킹시키고 타임아웃을 0s 으로 설정
        val timeoutRegistry = SimpleMeterRegistry()
        val blockingDataSource = mockk<DataSource>(relaxed = true)
        val blocker = CountDownLatch(1)

        every { blockingDataSource.connection } answers {
            blocker.await() // 타임아웃이 만료될 때까지 블로킹
            null
        }

        val timeoutMetrics =
            DependencyHealthMetrics(
                timeoutRegistry,
                blockingDataSource,
                FIXED_CLOCK,
                0L,
            )

        try {
            // when: timeoutSeconds=0 → Future.get(0, SECONDS) 즉시 타임아웃
            timeoutMetrics.poll()

            // then: DB 게이지는 0(타임아웃 판정)
            val dbGauge =
                timeoutRegistry
                    .find(DependencyHealthMetrics.METRIC_DEPENDENCY_UP)
                    .tag("component", DependencyHealthMetrics.COMPONENT_DB)
                    .gauge()
            assertThat(dbGauge).isNotNull
            assertThat(dbGauge!!.value()).isEqualTo(0.0)
        } finally {
            blocker.countDown() // 익스큐터 스레드 해제
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: 폴 완료 후 last_poll_timestamp 갱신
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("폴링 완료마다 last_poll_timestamp 갱신 — poll() 1회 후 타임스탬프 게이지 = 주입 Clock 의 epoch second")
    fun `폴링완료마다_last_poll_timestamp_갱신`() {
        // when
        metrics.poll()

        // then
        val timestampGauge =
            meterRegistry
                .find(DependencyHealthMetrics.METRIC_LAST_POLL_TIMESTAMP)
                .gauge()
        assertThat(timestampGauge).isNotNull
        assertThat(timestampGauge!!.value()).isEqualTo(FIXED_INSTANT.epochSecond.toDouble())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ──────────────────────────────────────────────────────────────────────────

    private fun componentGaugeValue(component: String): Double {
        val gauge =
            meterRegistry
                .find(DependencyHealthMetrics.METRIC_DEPENDENCY_UP)
                .tag("component", component)
                .gauge()
        assertThat(gauge).`as`("component=%s 게이지가 등록되어야 함", component).isNotNull
        return gauge!!.value()
    }

    private fun setupHealthyDb() {
        val mockConn = mockk<Connection>(relaxed = true)
        every { mockDataSource.connection } returns mockConn
        every { mockConn.isValid(any()) } returns true
    }

    private fun configureDbForFailureScenario(scenario: HealthCheckFailureScenario) {
        when (scenario) {
            HealthCheckFailureScenario.DOWN -> {
                val mockConn = mockk<Connection>(relaxed = true)
                every { mockDataSource.connection } returns mockConn
                every { mockConn.isValid(any()) } returns false
            }

            HealthCheckFailureScenario.OUT_OF_SERVICE -> {
                every { mockDataSource.connection } throws SQLException("데이터소스 연결 불가 — out of service")
            }

            HealthCheckFailureScenario.UNKNOWN -> {
                every { mockDataSource.connection } throws RuntimeException("예기치 않은 오류 — unknown")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 테스트 시나리오 열거형
    // ──────────────────────────────────────────────────────────────────────────

    enum class HealthCheckFailureScenario {
        /** isValid() 가 false 를 반환하는 경우 (연결은 열렸으나 DB 자체 불정상) */
        DOWN,

        /** getConnection() 에서 SQLException 이 발생하는 경우 (연결 자체 불가) */
        OUT_OF_SERVICE,

        /** 예기치 않은 RuntimeException 이 발생하는 경우 */
        UNKNOWN,
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
