package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class HttpOperatorImpl implements HttpOperator {

    @Value("${spring.myapp.toss-payments.http.read-timeout-millis}")
    private long readTimeoutMillis;

    @Override
    public <T> T requestGet(
            String url,
            Map<String, String> httpHeaderMap,
            Class<T> responseType
    ) {
        HttpHeaders headers = generateHttpHeaders(httpHeaderMap);
        HttpEntity<Object> httpEntity = new HttpEntity<>(headers);

        return new RestTemplate()
                .exchange(
                        url,
                        HttpMethod.GET,
                        httpEntity,
                        responseType
                )
                .getBody();
    }

    @Override
    public <T, E> E requestPost(
            String url,
            Map<String, String> httpHeaderMap,
            T body,
            Class<E> responseType
    ) {
        HttpHeaders httpHeaders = generateHttpHeaders(httpHeaderMap);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<T> httpEntity = createHttpEntity(httpHeaders, body);

        return new RestTemplate(getClientHttpRequestFactory())
                .exchange(
                        url,
                        HttpMethod.POST,
                        httpEntity,
                        responseType
                )
                .getBody();
    }

    private ClientHttpRequestFactory getClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        return factory;
    }

    protected HttpHeaders generateHttpHeaders(Map<String, String> httpHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAll(httpHeaders);

        return headers;
    }

    private <T> HttpEntity<T> createHttpEntity(HttpHeaders httpHeaders, T body) {
        return new HttpEntity<>(body, httpHeaders);
    }
}
