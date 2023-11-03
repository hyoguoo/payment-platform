package study.paymentintegrationserver.util;


import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

public final class HttpUtils {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";

    private HttpUtils() {
        throw new AssertionError();
    }

    public static <T, E> E requestPostWithBasicAuthorization(String url, String authorization, T body, Class<E> responseType) {
        HttpEntity<T> request = generateBasicAuthorizationHttpEntity(authorization, body);
        return new RestTemplate().postForObject(url, request, responseType);
    }

    private static <T> HttpEntity<T> generateBasicAuthorizationHttpEntity(String authorization, T body) {
        return new HttpEntity<>(body, generateBasicAuthorizationHttpHeaders(authorization));
    }

    private static HttpHeaders generateBasicAuthorizationHttpHeaders(String authorization) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(AUTHORIZATION_HEADER_NAME, BASIC_AUTHORIZATION_TYPE + authorization);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return httpHeaders;
    }
}
