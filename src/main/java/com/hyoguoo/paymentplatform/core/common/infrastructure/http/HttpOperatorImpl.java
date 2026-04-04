package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class HttpOperatorImpl implements HttpOperator {

    @Value("${spring.myapp.toss-payments.http.read-timeout-millis}")
    private long readTimeoutMillis;

    private final WebClient webClient = WebClient.builder().build();

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
                .block(Duration.ofMillis(readTimeoutMillis));
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
                .block(Duration.ofMillis(readTimeoutMillis));
    }

    protected Map<String, String> getAdditionalHeaders() {
        return Map.of();
    }

    private Map<String, String> mergeHeaders(Map<String, String> httpHeaderMap) {
        Map<String, String> merged = new HashMap<>(httpHeaderMap);
        merged.putAll(getAdditionalHeaders());
        return merged;
    }
}
