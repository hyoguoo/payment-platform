package com.hyoguoo.paymentplatform.pg.infrastructure.http;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient 기반 HTTP 구현체.
 * Spring Boot 3.4 / Spring Framework 6.2 의 동기 HTTP 클라이언트를 사용한다.
 * Kafka 컨슈머 스레드에서 호출되므로 동기 블로킹 모델이 자연스럽다.
 *
 * <p>타임아웃은 벤더 공통 {@code pg.http.*} 프로퍼티로 주입한다.
 *
 * <p>Spring Boot 3.2+ 는 {@code RestClient.Builder} auto-config 에서 {@code ObservationRegistry} 를 자동 설정한다.
 * Builder 를 생성자로 주입받아 HTTP 경계에서 traceparent 가 자동 전파된다.
 * 커스텀 requestFactory(connect/read timeout) 는 auto-config 설정을 상속하면서 적용한다.
 */
@Component
public class HttpOperatorImpl implements HttpOperator {

    private final RestClient restClient;

    public HttpOperatorImpl(
            RestClient.Builder restClientBuilder,
            @Value("${pg.http.connect-timeout-millis:3000}") int connectTimeoutMillis,
            @Value("${pg.http.read-timeout-millis:10000}") int readTimeoutMillis
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = restClientBuilder.clone()
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
