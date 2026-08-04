package com.hyoguoo.paymentplatform.pg.domain;

import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetryPolicy 단위 테스트.
 * 정책 파라미터: base=2s, multiplier=3, jitter=±25% equal jitter, MAX_ATTEMPTS=4.
 */
@DisplayName("RetryPolicy")
class RetryPolicyTest {

    private final SecureRandom rng = new SecureRandom();

    // -----------------------------------------------------------------------
    // shouldRetry 경계값
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shouldRetry — attempt=1,2,3은 true, attempt=4는 false")
    void shouldRetry_BoundaryValues() {
        assertThat(RetryPolicy.shouldRetry(1)).isTrue();
        assertThat(RetryPolicy.shouldRetry(2)).isTrue();
        assertThat(RetryPolicy.shouldRetry(3)).isTrue();
        assertThat(RetryPolicy.shouldRetry(4)).isFalse();
        assertThat(RetryPolicy.shouldRetry(5)).isFalse();
    }

    // -----------------------------------------------------------------------
    // computeBackoff 범위 검증 (attempt=1)
    // -----------------------------------------------------------------------

    @RepeatedTest(20)
    @DisplayName("computeBackoff(attempt=1) — base=2s*3^0=2s, jitter±25% → [1.5s, 2.5s]")
    void computeBackoff_Attempt1_ShouldBeInRange() {
        // base = 2s * 3^0 = 2s, jitter ±25% → [1.5s, 2.5s]
        Duration backoff = RetryPolicy.computeBackoff(1, rng);
        assertThat(backoff).isBetween(Duration.ofMillis(1500), Duration.ofMillis(2500));
    }

    // -----------------------------------------------------------------------
    // computeBackoff 범위 검증 (attempt=4 — DLQ 직전 마지막)
    // -----------------------------------------------------------------------

    @RepeatedTest(20)
    @DisplayName("computeBackoff(attempt=4) — base=2s*3^3=54s, jitter±25% → [40.5s, 67.5s]")
    void computeBackoff_Attempt4_ShouldBeInRange() {
        // base = 2s * 3^3 = 54s, jitter ±25% → [40.5s, 67.5s]
        Duration backoff = RetryPolicy.computeBackoff(4, rng);
        assertThat(backoff).isBetween(Duration.ofMillis(40500), Duration.ofMillis(67500));
    }

    // -----------------------------------------------------------------------
    // 실제로 예약되는 마지막 재시도 회차와 좀비 회수 타임아웃의 관계 고정
    // attempt=MAX_ATTEMPTS(4)는 shouldRetry 가 false 를 반환해 DLQ 경로로 빠지므로,
    // 실제 재시도 예약에 computeBackoff 가 쓰이는 마지막 회차는 MAX_ATTEMPTS-1(=3)이다.
    // -----------------------------------------------------------------------

    @RepeatedTest(20)
    @DisplayName("computeBackoff(attempt=MAX_ATTEMPTS-1) — 좀비 회수 타임아웃(60초, pg-service "
            + "application.yml inbox-polling-worker.in-progress-timeout-ms)보다 항상 짧다")
    void computeBackoff_LastScheduledRetryAttempt_IsAlwaysBelowZombieRecoveryTimeout() {
        Duration backoff = RetryPolicy.computeBackoff(RetryPolicy.MAX_ATTEMPTS - 1, rng);
        assertThat(backoff).isLessThan(Duration.ofMillis(60_000));
    }
}
