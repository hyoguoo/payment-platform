package com.hyoguoo.paymentplatform.payment.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RetryPolicyProperties.class)
public class PaymentConfig {
}
