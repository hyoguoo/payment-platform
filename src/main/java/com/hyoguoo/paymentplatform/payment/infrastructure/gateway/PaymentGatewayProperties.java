package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "payment.gateway")
public class PaymentGatewayProperties {

    private PaymentGatewayType type = PaymentGatewayType.TOSS;
    private Toss toss = new Toss();

    @Getter
    @Setter
    public static class Toss {
        private String baseUrl = "https://api.tosspayments.com";
        private String secretKey;
        private int connectTimeout = 3000;
        private int readTimeout = 10000;
    }
}
