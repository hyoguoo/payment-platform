package com.hyoguoo.paymentplatform.pg.domain;

import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetryPolicy 단위 테스트.
 * ADR-30: base=2s, multiplier=3, jitter=±25% equal jitter, MAX_ATTEMPTS=4.
 */
@DisplayName("RetryPolicy")
class RetryPolicyTest {

    private final SecureRandom rng = new SecureRandom();

    // -----------------------------------------------------------------------
    // shouldRetry 경계값
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shouldRetry — attempt=1,2,3은 true, attempt=4는 false (불변식 6)")
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
}
