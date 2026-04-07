package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    @DisplayName("retryCount가 maxAttempts 미만이면 isExhausted는 false를 반환한다.")
    void isExhausted_retryCount가_maxAttempts_미만이면_false() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 1000L, 60000L);

        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(0)).isFalse();
    }

    @Test
    @DisplayName("retryCount가 maxAttempts 이상이면 isExhausted는 true를 반환한다.")
    void isExhausted_retryCount가_maxAttempts_이상이면_true() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 1000L, 60000L);

        assertThat(policy.isExhausted(5)).isTrue();
        assertThat(policy.isExhausted(6)).isTrue();
    }

    @Test
    @DisplayName("FIXED backoff은 retryCount에 관계없이 항상 baseDelayMs를 반환한다.")
    void nextDelay_FIXED_항상_baseDelayMs_반환() {
        RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 3000L, 60000L);

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofMillis(3000));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(3000));
        assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(3000));
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
}
