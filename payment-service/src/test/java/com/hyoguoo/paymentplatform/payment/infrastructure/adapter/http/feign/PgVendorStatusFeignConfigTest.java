package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.exception.PgVendorStatusQueryFailedException;
import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * PgVendorStatusFeignConfig ErrorDecoder — 상태 코드와 무관하게 확인 불가로 이어지는 예외 하나로 접힌다.
 *
 * <p>{@code PgFeignConfig} 는 4xx/5xx 를 서로 다른 도메인 예외로 나누지만(호출부가 각각 다르게
 * 반응해야 하므로), 이 클라이언트는 {@code PgVendorStatusHttpAdapter} 가 모든 {@code RuntimeException}
 * 을 확인 불가로 접어 반환하므로 상태 코드별로 예외 타입을 나눌 필요가 없다. 세 구간을 각각 테스트해
 * 이 무차별 흡수가 의도임을 고정한다.
 */
@DisplayName("PgVendorStatusFeignConfig ErrorDecoder — 상태 코드 무관 확인 불가 예외로 통일")
class PgVendorStatusFeignConfigTest {

    private final ErrorDecoder decoder = new PgVendorStatusFeignConfig().pgVendorStatusErrorDecoder();

    @Test
    @DisplayName("404 → PgVendorStatusQueryFailedException")
    void decode_NotFound_ShouldReturnQueryFailedException() {
        Response response = buildResponse(404, "{\"message\":\"not found\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(PgVendorStatusQueryFailedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    @DisplayName("429/502/503/504 → PgVendorStatusQueryFailedException")
    void decode_RetryableStatus_ShouldReturnQueryFailedException(int status) {
        Response response = buildResponse(status, "{\"message\":\"retryable\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(PgVendorStatusQueryFailedException.class);
    }

    @Test
    @DisplayName("그 외 5xx(500) → PgVendorStatusQueryFailedException")
    void decode_InternalServerError_ShouldReturnQueryFailedException() {
        Response response = buildResponse(500, "{\"error\":\"boom\"}");

        Exception exception = decoder.decode("methodKey", response);

        assertThat(exception).isInstanceOf(PgVendorStatusQueryFailedException.class);
    }

    private Response buildResponse(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("test")
                .request(Request.create(
                        Request.HttpMethod.GET,
                        "/api/v1/confirmations/order-1/vendor-status",
                        Map.of(),
                        null,
                        StandardCharsets.UTF_8,
                        null))
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }
}
