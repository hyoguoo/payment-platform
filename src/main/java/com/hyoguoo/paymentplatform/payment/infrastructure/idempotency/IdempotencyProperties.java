package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "payment.idempotency")
public class IdempotencyProperties {

    private final long maximumSize;
    private final long expireAfterWriteSeconds;

    public IdempotencyProperties(
            @DefaultValue("10000") long maximumSize,
            @DefaultValue("10") long expireAfterWriteSeconds
    ) {
        this.maximumSize = maximumSize;
        this.expireAfterWriteSeconds = expireAfterWriteSeconds;
    }
}
