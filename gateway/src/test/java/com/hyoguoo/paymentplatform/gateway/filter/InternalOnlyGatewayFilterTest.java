package com.hyoguoo.paymentplatform.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class InternalOnlyGatewayFilterTest {

    private InternalOnlyGatewayFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalOnlyGatewayFilter();
    }

    @Test
    @DisplayName("/internal/** 경로 요청은 403 Forbidden을 반환하고 체인을 중단한다")
    void filter_WhenInternalPath_Returns403() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/internal/pg/status")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = exch -> {
            throw new AssertionError("chain.filter()가 호출되어서는 안 된다");
        };

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("/internal/ 경로가 아닌 요청은 다음 필터 체인으로 전달한다")
    void filter_WhenNonInternalPath_DelegatesToChain() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/payments/123")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        boolean[] chainCalled = {false};
        GatewayFilterChain chain = exch -> {
            chainCalled[0] = true;
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled[0]).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
