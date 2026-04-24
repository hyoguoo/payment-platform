package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Component
public class HttpOperatorImpl implements HttpOperator {

    private final WebClient webClient;

    /**
     * Boot auto-config WebClient.Builder 를 주입받아 observationRegistry 자동 적용(D6).
     *
     * <p>Spring Boot 3.2+ 는 {@code WebClient.Builder} auto-config 에서 {@code ObservationRegistry}
     * 를 자동 설정한다. Builder 를 주입받기만 하면 HTTP 경계에서 traceparent 자동 전파됨.
     * 커스텀 connector(connect/read timeout) 는 {@code mutate()} 를 통해 auto-config 설정을 상속하며 적용.
     */
    public HttpOperatorImpl(
            WebClient.Builder webClientBuilder,
            @Value("${spring.gateway.toss.connect-timeout:3000}") int connectTimeoutMillis,
            @Value("${spring.myapp.toss-payments.http.read-timeout-millis}") long readTimeoutMillis
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(Duration.ofMillis(readTimeoutMillis));
        this.webClient = webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public <T> T requestGet(
            String url,
            Map<String, String> httpHeaderMap,
            Class<T> responseType
    ) {
        Map<String, String> mergedHeaders = mergeHeaders(httpHeaderMap);
        return webClient.get()
                .uri(url)
                .headers(headers -> headers.setAll(mergedHeaders))
                .retrieve()
                .bodyToMono(responseType)
                .block();
    }

    @Override
    public <T, E> E requestPost(
            String url,
            Map<String, String> httpHeaderMap,
            T body,
            Class<E> responseType
    ) {
        Map<String, String> mergedHeaders = mergeHeaders(httpHeaderMap);
        return webClient.post()
                .uri(url)
                .headers(headers -> headers.setAll(mergedHeaders))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(responseType)
                .block();
    }

    protected Map<String, String> getAdditionalHeaders() {
        return Map.of();
    }

    private Map<String, String> mergeHeaders(Map<String, String> httpHeaderMap) {
        Map<String, String> additional = getAdditionalHeaders();
        if (additional.isEmpty()) {
            return httpHeaderMap;
        }
        Map<String, String> merged = new HashMap<>(httpHeaderMap);
        merged.putAll(additional);
        return merged;
    }
}
