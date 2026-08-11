package com.hyoguoo.paymentplatform.pg.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("DependencyHealthMetrics 단위 테스트")
class DependencyHealthMetricsTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private DataSource mockDataSource;
    private DependencyHealthMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        mockDataSource = mock(DataSource.class);

        setupHealthyDb();

        metrics = new DependencyHealthMetrics(meterRegistry, mockDataSource, FIXED_CLOCK, 2L);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: UP → 게이지 1
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("health UP 상태면 게이지 1 — DB 정상이면 게이지 1.0")
    void health_UP_상태면_게이지1() {
        // when
        metrics.poll();

        // then
        assertThat(componentGaugeValue(meterRegistry, DependencyHealthMetrics.COMPONENT_DB)).isEqualTo(1.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: 비UP 시나리오 → 게이지 0
    // ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "health_DOWN_상태면_게이지0 — {0} 시나리오에서 db 게이지 0.0")
    @EnumSource(HealthCheckFailureScenario.class)
    @DisplayName("health DOWN 상태면 게이지 0 — DOWN·OUT_OF_SERVICE·UNKNOWN 시나리오에서 DB 게이지 0.0")
    void health_DOWN_상태면_게이지0(HealthCheckFailureScenario scenario) throws Exception {
        // given: 각 시나리오에 맞게 DB 목 재구성
        configureDbForFailureScenario(scenario);

        // when
        metrics.poll();

        // then
        assertThat(componentGaugeValue(meterRegistry, DependencyHealthMetrics.COMPONENT_DB)).isEqualTo(0.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: 타임아웃 → 게이지 0
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("health 조회 타임아웃이면 게이지 0 — DB 조회가 블로킹 상태에서 타임아웃 가드 동작 시 DB 게이지 0.0")
    void health조회_타임아웃이면_게이지0() throws Exception {
        // given: DB 조회를 영구 블로킹시키고 타임아웃을 0s 으로 설정
        SimpleMeterRegistry timeoutRegistry = new SimpleMeterRegistry();
        DataSource blockingDataSource = mock(DataSource.class);
        CountDownLatch blocker = new CountDownLatch(1);

        when(blockingDataSource.getConnection()).thenAnswer(inv -> {
            blocker.await(); // 타임아웃이 만료될 때까지 블로킹
            return null;
        });

        DependencyHealthMetrics timeoutMetrics = new DependencyHealthMetrics(
                timeoutRegistry, blockingDataSource, FIXED_CLOCK, 0L);

        try {
            // when: timeoutSeconds=0 → Future.get(0, SECONDS) 즉시 타임아웃
            timeoutMetrics.poll();

            // then: DB 게이지는 0(타임아웃 판정)
            Gauge dbGauge = timeoutRegistry.find(DependencyHealthMetrics.METRIC_DEPENDENCY_UP)
                    .tag("component", DependencyHealthMetrics.COMPONENT_DB)
                    .gauge();
            assertThat(dbGauge).isNotNull();
            assertThat(dbGauge.value()).isEqualTo(0.0);
        } finally {
            blocker.countDown(); // 익스큐터 스레드 해제
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: 폴 완료 후 last_poll_timestamp 갱신
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("폴링 완료마다 last_poll_timestamp 갱신 — poll() 1회 후 타임스탬프 게이지 = 주입 Clock 의 epoch second")
    void 폴링완료마다_last_poll_timestamp_갱신() {
        // when
        metrics.poll();

        // then
        Gauge timestampGauge = meterRegistry
                .find(DependencyHealthMetrics.METRIC_LAST_POLL_TIMESTAMP)
                .gauge();
        assertThat(timestampGauge).isNotNull();
        assertThat(timestampGauge.value()).isEqualTo((double) FIXED_INSTANT.getEpochSecond());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ──────────────────────────────────────────────────────────────────────────

    private double componentGaugeValue(SimpleMeterRegistry registry, String component) {
        Gauge gauge = registry.find(DependencyHealthMetrics.METRIC_DEPENDENCY_UP)
                .tag("component", component)
                .gauge();
        assertThat(gauge).as("component=%s 게이지가 등록되어야 함", component).isNotNull();
        return gauge.value();
    }

    private void setupHealthyDb() throws Exception {
        Connection mockConn = mock(Connection.class);
        when(mockDataSource.getConnection()).thenReturn(mockConn);
        when(mockConn.isValid(anyInt())).thenReturn(true);
    }

    private void configureDbForFailureScenario(HealthCheckFailureScenario scenario) throws Exception {
        if (scenario == HealthCheckFailureScenario.DOWN) {
            Connection mockConn = mock(Connection.class);
            when(mockDataSource.getConnection()).thenReturn(mockConn);
            when(mockConn.isValid(anyInt())).thenReturn(false);
        } else if (scenario == HealthCheckFailureScenario.OUT_OF_SERVICE) {
            when(mockDataSource.getConnection())
                    .thenThrow(new SQLException("데이터소스 연결 불가 — out of service"));
        } else {
            when(mockDataSource.getConnection())
                    .thenThrow(new RuntimeException("예기치 않은 오류 — unknown"));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 테스트 시나리오 열거형
    // ──────────────────────────────────────────────────────────────────────────

    enum HealthCheckFailureScenario {
        /** isValid() 가 false 를 반환하는 경우 (연결은 열렸으나 DB 자체 불정상) */
        DOWN,
        /** getConnection() 에서 SQLException 이 발생하는 경우 (연결 자체 불가) */
        OUT_OF_SERVICE,
        /** 예기치 않은 RuntimeException 이 발생하는 경우 */
        UNKNOWN
    }
}
