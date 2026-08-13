package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TossPaymentGatewayStrategy 중복 승인 응답(ALREADY_PROCESSED_PAYMENT) — 예외만 던지고
 * 이벤트는 발행하지 않는지 검증한다.
 */
@DisplayName("TossPaymentGatewayStrategy 중복 승인 응답 — 예외 단일 신호")
class TossPaymentGatewayStrategyDuplicateEventTest {

    private static final String ORDER_ID = "order-duplicate-approval";

    private HttpOperator httpOperator;
    private TossPaymentGatewayStrategy strategy;

    @BeforeEach
    void setUp() {
        httpOperator = mock(HttpOperator.class);
        EncodeUtils encodeUtils = mock(EncodeUtils.class);
        when(encodeUtils.encodeBase64(anyString())).thenReturn("dummy-basic");

        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        strategy = new TossPaymentGatewayStrategy(
                httpOperator, encodeUtils, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(strategy, "secretKey", "secret-dummy");
        ReflectionTestUtils.setField(strategy, "tossApiUrl", "https://api.tosspayments.com");
    }

    @Test
    @DisplayName("confirm_중복승인응답_이벤트를_발행하지_않고_예외만_던진다")
    void confirm_중복승인응답_이벤트를_발행하지_않고_예외만_던진다() {
        boolean hasEventPublisherField = Arrays.stream(TossPaymentGatewayStrategy.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type == ApplicationEventPublisher.class);
        assertThat(hasEventPublisherField)
                .as("TossPaymentGatewayStrategy 에 ApplicationEventPublisher 필드가 남아 있으면 안 됨 — 이벤트 발행 경로 제거")
                .isFalse();

        String failBody = "{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}";
        RestClientResponseException exception = new RestClientResponseException(
                "Bad Request", 400, "Bad Request", new HttpHeaders(),
                failBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(httpOperator.requestPost(anyString(), anyMap(), any(), eq(TossPaymentApiResponse.class)))
                .thenThrow(exception);

        PgConfirmRequest request = new PgConfirmRequest(ORDER_ID, "pk-001", BigDecimal.valueOf(1000), PgVendorType.TOSS);

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);
    }
}
