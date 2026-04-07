package com.hyoguoo.paymentplatform.payment.application.config;

import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "payment.retry")
public class RetryPolicyProperties {

    private final int maxAttempts;
    private final BackoffType backoffType;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public RetryPolicyProperties(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("FIXED") BackoffType backoffType,
            @DefaultValue("5000") long baseDelayMs,
            @DefaultValue("60000") long maxDelayMs
    ) {
        this.maxAttempts = maxAttempts;
        this.backoffType = backoffType;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public RetryPolicy toRetryPolicy() {
        return new RetryPolicy(maxAttempts, backoffType, baseDelayMs, maxDelayMs);
    }
}
