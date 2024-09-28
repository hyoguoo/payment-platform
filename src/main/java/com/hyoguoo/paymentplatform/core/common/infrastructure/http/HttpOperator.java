package com.hyoguoo.paymentplatform.core.common.infrastructure.http;

import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class HttpOperator {

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

    public <T, E> E requestPost(
            String url,
            Map<String, String> httpHeaderMap,
            T body,
            Class<E> responseType
    ) {
        HttpHeaders httpHeaders = generateHttpHeaders(httpHeaderMap);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
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

    private HttpHeaders generateHttpHeaders(Map<String, String> httpHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAll(httpHeaders);

        return headers;
    }

    private <T> HttpEntity<T> createHttpEntity(HttpHeaders httpHeaders, T body) {
        return new HttpEntity<>(body, httpHeaders);
    }
}
