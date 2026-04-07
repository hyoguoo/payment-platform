package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import java.time.Duration;

public record RetryPolicy(
        int maxAttempts,
        BackoffType backoffType,
        long baseDelayMs,
        long maxDelayMs
) {

    public boolean isExhausted(int retryCount) {
        return retryCount >= maxAttempts;
    }

    public Duration nextDelay(int retryCount) {
        return switch (backoffType) {
            case FIXED -> Duration.ofMillis(baseDelayMs);
            case EXPONENTIAL -> Duration.ofMillis(
                    Math.min(baseDelayMs * (1L << retryCount), maxDelayMs)
            );
        };
    }
}
