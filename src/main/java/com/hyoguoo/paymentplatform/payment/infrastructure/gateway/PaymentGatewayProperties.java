package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@Getter
@ConfigurationProperties(prefix = "payment.gateway")
public class PaymentGatewayProperties {

    private final PaymentGatewayType type;
    private final Toss toss;

    public PaymentGatewayProperties(
            @DefaultValue("TOSS") PaymentGatewayType type,
            Toss toss
    ) {
        this.type = type;
        this.toss = toss;
    }

    @Getter
    public static class Toss {

        private final String baseUrl;
        private final String secretKey;
        private final int connectTimeout;
        private final int readTimeout;

        public Toss(
                @DefaultValue("https://api.tosspayments.com") String baseUrl,
                String secretKey,
                @DefaultValue("3000") int connectTimeout,
                @DefaultValue("10000") int readTimeout
        ) {
            this.baseUrl = baseUrl;
            this.secretKey = secretKey;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
        }
    }
}
