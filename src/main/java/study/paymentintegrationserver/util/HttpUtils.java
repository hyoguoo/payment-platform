package study.paymentintegrationserver.util;


import java.util.Optional;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

public final class HttpUtils {

    public static final String IDEMPOTENCY_KEY_HEADER_NAME = "Idempotency-Key";
    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";

    private HttpUtils() {
        throw new AssertionError();
    }

    public static <T> Optional<T> requestGetWithBasicAuthorization(
            String url,
            String authorization,
            Class<T> responseType
    ) {
        HttpHeaders httpHeaders = generateBasicAuthorizationHttpHeaders(authorization);
        HttpEntity<Object> httpEntity = new HttpEntity<>(httpHeaders);

        try {
            ResponseEntity<T> response = new RestTemplate().exchange(
                    url,
                    HttpMethod.GET,
                    httpEntity,
                    responseType
            );

            return Optional.ofNullable(response.getBody());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new HttpClientErrorException(e.getStatusCode(), e.getMessage());
        }
    }

    public static <T, E> E requestPostWithBasicAuthorization(
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

        ResponseEntity<E> response = new RestTemplate().exchange(
                url,
                HttpMethod.POST,
                httpEntity,
                responseType
        );

        return response.getBody();
    }

    private static <T> HttpEntity<T> createHttpEntity(HttpHeaders httpHeaders, T body) {
        return new HttpEntity<>(body, httpHeaders);
    }

    private static HttpHeaders generateBasicAuthorizationHttpHeaders(String authorization) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(AUTHORIZATION_HEADER_NAME, BASIC_AUTHORIZATION_TYPE + authorization);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return httpHeaders;
    }

    private static HttpHeaders generateBasicAuthorizationHttpHeaders(String authorization,
            String idempotencyKey) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(AUTHORIZATION_HEADER_NAME, BASIC_AUTHORIZATION_TYPE + authorization);
        httpHeaders.add(IDEMPOTENCY_KEY_HEADER_NAME, idempotencyKey);
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        return httpHeaders;
    }
}
