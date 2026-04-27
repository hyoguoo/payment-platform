package com.hyoguoo.paymentplatform.payment.core.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class HttpClientConfig {

    /**
     * cross-service HTTP 호출(payment → product/user) 전용 LoadBalanced WebClient.Builder.
     *
     * <p>Spring Cloud LoadBalancer 가 base-url 을 logical service name(http://product-service)
     * 으로 해석하여 Eureka 인스턴스 list 에서 round-robin 선택한다.
     *
     * <p>HttpOperatorImpl 이 사용하는 외부 PG(Toss/NicePay) builder 와는 별개 빈 — 외부 host
     * (api.tosspayments.com 등)는 LB 통과시키지 않기 위해 내부 cross-service 호출에만 한정한다.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
