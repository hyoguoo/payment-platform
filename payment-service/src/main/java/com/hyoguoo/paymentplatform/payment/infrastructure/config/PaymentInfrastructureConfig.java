package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.infrastructure.idempotency.IdempotencyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({IdempotencyProperties.class})
public class PaymentInfrastructureConfig {
}
