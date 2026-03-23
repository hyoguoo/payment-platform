package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.idempotency")
public class IdempotencyProperties {

    private long maximumSize = 10_000;
    private long expireAfterWriteSeconds = 10;
}
