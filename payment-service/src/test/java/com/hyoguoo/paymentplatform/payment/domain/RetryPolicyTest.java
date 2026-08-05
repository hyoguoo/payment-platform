package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    @DisplayName("FIXED backoff은 retryCount에 관계없이 항상 baseDelayMs를 반환한다.")
    void nextDelay_FIXED_항상_baseDelayMs_반환() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 3000L, 60000L);

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofMillis(3000));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(3000));
        assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(3000));
    }

    @Test
    @DisplayName("고정 간격은 회차와 무관하게 같은 값이다.")
    void nextDelay_FIXED_회차와_무관하게_같은_값() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 3000L, 60000L);

        assertThat(policy.nextDelay(0))
                .isEqualTo(policy.nextDelay(1_000))
                .isEqualTo(policy.nextDelay(1_000_000));
    }

    @Test
    @DisplayName("EXPONENTIAL backoff은 retryCount에 따라 지수적으로 증가한다.")
    void nextDelay_EXPONENTIAL_retryCount에_따라_지수_증가() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);

        // baseDelayMs * 2^retryCount
        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofMillis(1000)); // 1000 * 1
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(2000)); // 1000 * 2
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(4000)); // 1000 * 4
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(8000)); // 1000 * 8
    }

    @Test
    @DisplayName("EXPONENTIAL backoff은 maxDelayMs를 초과하지 않는다.")
    void nextDelay_EXPONENTIAL_maxDelayMs_초과하지_않음() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 10000L);

        assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(10000));
        assertThat(policy.nextDelay(20)).isEqualTo(Duration.ofMillis(10000));
    }

    @Test
    @DisplayName("지수 백오프는 회차가 커져도 최대값을 넘지 않는다.")
    void nextDelay_EXPONENTIAL_회차가_커져도_maxDelayMs_넘지_않음() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);

        // retryCount=54부터 baseDelayMs * (1L << retryCount) 가 long 곱셈에서 넘친다(상한 처리 없으면 회귀).
        assertThat(policy.nextDelay(54)).isEqualTo(Duration.ofMillis(60000));
        assertThat(policy.nextDelay(100)).isEqualTo(Duration.ofMillis(60000));
    }

    @Test
    @DisplayName("지수 백오프는 회차가 커져도 시프트 넘침으로 음수가 되지 않는다.")
    void nextDelay_EXPONENTIAL_회차가_커져도_음수가_되지_않음() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);

        assertThat(policy.nextDelay(54).toMillis()).isNotNegative();
        assertThat(policy.nextDelay(60).toMillis()).isNotNegative();
    }
}
