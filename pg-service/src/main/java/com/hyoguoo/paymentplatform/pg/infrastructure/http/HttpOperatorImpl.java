package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient 기반 HTTP 구현체.
 * Spring Boot 3.4 / Spring Framework 6.2의 동기 HTTP 클라이언트를 사용한다.
 * Kafka 컨슈머 스레드에서 호출되므로 동기 블로킹 모델이 자연스럽다.
 *
 * <p>타임아웃은 벤더 공통 {@code pg.http.*} 프로퍼티로 주입한다.
 */
@Component
public class HttpOperatorImpl implements HttpOperator {

    @Value("${pg.http.connect-timeout-millis:3000}")
    private int connectTimeoutMillis;

    @Value("${pg.http.read-timeout-millis:10000}")
    private int readTimeoutMillis;

    private RestClient restClient;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public <T> T requestGet(
            String url,
            Map<String, String> httpHeaderMap,
            Class<T> responseType
    ) {
        return restClient.get()
                .uri(url)
                .headers(headers -> headers.setAll(httpHeaderMap))
                .retrieve()
                .body(responseType);
    }

    @Override
    public <T, E> E requestPost(
            String url,
            Map<String, String> httpHeaderMap,
            T body,
            Class<E> responseType
    ) {
        return restClient.post()
                .uri(url)
                .headers(headers -> headers.setAll(httpHeaderMap))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(responseType);
    }
}
