package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class HttpOperator {

    public static final String IDEMPOTENCY_KEY_HEADER_NAME = "Idempotency-Key";
    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";

    public <T> T requestGetWithBasicAuthorization(
            String url,
            String authorization,
            Class<T> responseType
    ) {
        HttpHeaders httpHeaders = generateBasicAuthorizationHttpHeaders(authorization);
        HttpEntity<Object> httpEntity = new HttpEntity<>(httpHeaders);

        return new RestTemplate()
                .exchange(
                        url,
                        HttpMethod.GET,
                        httpEntity,
                        responseType
                )
                .getBody();
    }

    public <T, E> E requestPostWithBasicAuthorization(
            String url,
            String authorization,
            String idempotencyKey,
            T body,
            Class<E> responseType
    ) {
        HttpHeaders httpHeaders = generateBasicAuthorizationHttpHeaders(
                authorization,
                idempotencyKey
        );
        HttpEntity<T> httpEntity = createHttpEntity(httpHeaders, body);

        return new RestTemplate()
                .exchange(
                        url,
                        HttpMethod.POST,
                        httpEntity,
                        responseType
                )
                .getBody();
    }

    private <T> HttpEntity<T> createHttpEntity(HttpHeaders httpHeaders, T body) {
        return new HttpEntity<>(body, httpHeaders);
    }

    private HttpHeaders generateBasicAuthorizationHttpHeaders(String authorization) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(AUTHORIZATION_HEADER_NAME, BASIC_AUTHORIZATION_TYPE + authorization);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return httpHeaders;
    }

    private static HttpHeaders generateBasicAuthorizationHttpHeaders(
            String authorization,
            String idempotencyKey
    ) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(AUTHORIZATION_HEADER_NAME, BASIC_AUTHORIZATION_TYPE + authorization);
        httpHeaders.add(IDEMPOTENCY_KEY_HEADER_NAME, idempotencyKey);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return httpHeaders;
    }
}
