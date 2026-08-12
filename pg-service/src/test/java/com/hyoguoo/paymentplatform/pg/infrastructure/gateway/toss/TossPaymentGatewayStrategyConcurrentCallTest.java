package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayConcurrentCallException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TossPaymentGatewayStrategy 승인 호출이 벤더로부터 처리 중 거부(IDEMPOTENT_REQUEST_PROCESSING,
 * HTTP 409)를 받았을 때 전용 예외로 전파하는지 검증한다 — 겹친 호출이 재시도 대상(UNKNOWN)으로
 * 흡수돼 시도횟수를 소모하지 않게 하는 근거.
 */
@DisplayName("TossPaymentGatewayStrategy 처리 중 거부 전파")
class TossPaymentGatewayStrategyConcurrentCallTest {

    private static final String ORDER_ID = "order-concurrent-call";

    private HttpOperator httpOperator;
    private TossPaymentGatewayStrategy strategy;

    @BeforeEach
    void setUp() {
        httpOperator = mock(HttpOperator.class);
        EncodeUtils encodeUtils = mock(EncodeUtils.class);
        when(encodeUtils.encodeBase64(anyString())).thenReturn("dummy-basic");

        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        strategy = new TossPaymentGatewayStrategy(
                httpOperator,
                encodeUtils,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper(),
                clock);
        ReflectionTestUtils.setField(strategy, "secretKey", "secret-dummy");
        ReflectionTestUtils.setField(strategy, "tossApiUrl", "https://api.tosspayments.com");
    }

    @Test
    @DisplayName("처리중거부_응답이면_전용예외_전파")
    void 처리중거부_응답이면_전용예외_전파() {
        String failBody = "{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"이미 처리 중인 요청입니다.\"}";
        RestClientResponseException exception = new RestClientResponseException(
                "Conflict", 409, "Conflict", new HttpHeaders(),
                failBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(httpOperator.requestPost(anyString(), anyMap(), any(), eq(TossPaymentApiResponse.class)))
                .thenThrow(exception);

        PgConfirmRequest request = new PgConfirmRequest(ORDER_ID, "pk-001", BigDecimal.valueOf(1000), PgVendorType.TOSS);

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayConcurrentCallException.class);
    }
}
