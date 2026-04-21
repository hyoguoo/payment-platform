package com.hyoguoo.paymentplatform.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * /internal/** 경로 외부 노출 차단 필터 (ADR-21, ADR-02).
 *
 * <p>Gateway 레벨에서 {@code /internal/} 로 시작하는 요청을 외부 클라이언트로부터 차단하여
 * PG 서비스 내부 API(예: {@code GET /internal/pg/status})가 외부에 노출되지 않도록 한다.
 *
 * <p>매칭 시 즉시 {@code 403 Forbidden}을 반환하고 다운스트림 라우팅을 중단한다.
 * 우선순위는 {@link Ordered#HIGHEST_PRECEDENCE} + 1로 설정하여 {@link TraceContextPropagationFilter}
 * 직후, 다른 필터 이전에 실행되도록 보장한다.
 */
@Component
public class InternalOnlyGatewayFilter implements GlobalFilter, Ordered {

    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith(INTERNAL_PATH_PREFIX)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
