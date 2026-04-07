package com.hyoguoo.paymentplatform.payment.application.config;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.retry")
public class RetryPolicyProperties {

    private int maxAttempts = 5;
    private BackoffType backoffType = BackoffType.FIXED;
    private long baseDelayMs = 5000;
    private long maxDelayMs = 60000;
}
