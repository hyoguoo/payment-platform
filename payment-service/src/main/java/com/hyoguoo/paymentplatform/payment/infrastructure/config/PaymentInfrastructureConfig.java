package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayProperties;
import com.hyoguoo.paymentplatform.payment.infrastructure.idempotency.IdempotencyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({IdempotencyProperties.class, PaymentGatewayProperties.class})
public class PaymentInfrastructureConfig {
}
