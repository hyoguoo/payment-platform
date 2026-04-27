package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserFeignConfig ErrorDecoder — 4xx/5xx → 도메인 예외 매핑")
class UserFeignConfigTest {

    private final ErrorDecoder decoder = new UserFeignConfig().userErrorDecoder();

    @Test
    @DisplayName("404 → UserNotFoundException")
    void decode_NotFound_ShouldReturnUserNotFoundException() {
        Response response = buildResponse(404, "{\"message\":\"not found\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("503 → UserServiceRetryableException")
    void decode_ServiceUnavailable_ShouldReturnRetryable() {
        Response response = buildResponse(503, "{\"message\":\"service unavailable\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(UserServiceRetryableException.class);
    }

    @Test
    @DisplayName("429 → UserServiceRetryableException")
    void decode_TooManyRequests_ShouldReturnRetryable() {
        Response response = buildResponse(429, "{\"message\":\"too many requests\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(UserServiceRetryableException.class);
    }

    @Test
    @DisplayName("500 → IllegalStateException (미분류 서버 에러, message 에 status 포함)")
    void decode_InternalServerError_ShouldReturnIllegalState() {
        Response response = buildResponse(500, "{\"error\":\"boom\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=500");
    }

    private Response buildResponse(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("test")
                .request(Request.create(
                        Request.HttpMethod.GET,
                        "/api/v1/users/1",
                        Map.of(),
                        null,
                        StandardCharsets.UTF_8,
                        null))
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }
}
