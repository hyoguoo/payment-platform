package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiResponse;
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
 * NicepayPaymentGatewayStrategy 중복 승인 응답(2201) — HTTP 성공 body 실패 / HTTP 오류 응답
 * 두 경로 모두 예외만 던지고 이벤트는 발행하지 않는지 검증한다.
 */
@DisplayName("NicepayPaymentGatewayStrategy 중복 승인 응답 — 예외 단일 신호")
class NicepayPaymentGatewayStrategyDuplicateEventTest {

    private static final String ORDER_ID = "order-duplicate-approval";
    private static final String DUPLICATE_RESULT_CODE = "2201";

    private HttpOperator httpOperator;
    private NicepayPaymentGatewayStrategy strategy;

    @BeforeEach
    void setUp() {
        httpOperator = mock(HttpOperator.class);
        EncodeUtils encodeUtils = mock(EncodeUtils.class);
        when(encodeUtils.encodeBase64(anyString())).thenReturn("dummy-basic");

        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        strategy = new NicepayPaymentGatewayStrategy(
                httpOperator, encodeUtils, new ObjectMapper(), clock);
        ReflectionTestUtils.setField(strategy, "clientKey", "S2_dummy");
        ReflectionTestUtils.setField(strategy, "secretKey", "secret-dummy");
        ReflectionTestUtils.setField(strategy, "nicepayApiUrl", "https://sandbox-api.nicepay.co.kr");
    }

    @Test
    @DisplayName("confirm_본문실패응답_2201_이벤트없이_예외만")
    void confirm_본문실패응답_2201_이벤트없이_예외만() {
        assertNoEventPublisherField();

        NicepayPaymentApiResponse response = new NicepayPaymentApiResponse(
                DUPLICATE_RESULT_CODE, "이미 처리된 결제입니다.", "tid-001", ORDER_ID,
                BigDecimal.valueOf(1000), null, null);
        when(httpOperator.requestPost(
                anyString(), anyMap(), any(), eq(NicepayPaymentApiResponse.class)))
                .thenReturn(response);

        PgConfirmRequest request = new PgConfirmRequest(
                ORDER_ID, "tid-001", BigDecimal.valueOf(1000), PgVendorType.NICEPAY);

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);
    }

    @Test
    @DisplayName("confirm_HTTP오류응답_2201_이벤트없이_예외만")
    void confirm_HTTP오류응답_2201_이벤트없이_예외만() {
        assertNoEventPublisherField();

        String failBody = "{\"resultCode\":\"2201\",\"resultMsg\":\"이미 처리된 결제입니다.\"}";
        RestClientResponseException exception = new RestClientResponseException(
                "Bad Request", 400, "Bad Request", new HttpHeaders(),
                failBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(httpOperator.requestPost(
                anyString(), anyMap(), any(), eq(NicepayPaymentApiResponse.class)))
                .thenThrow(exception);

        PgConfirmRequest request = new PgConfirmRequest(
                ORDER_ID, "tid-001", BigDecimal.valueOf(1000), PgVendorType.NICEPAY);

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);
    }

    private void assertNoEventPublisherField() {
        boolean hasEventPublisherField = Arrays.stream(NicepayPaymentGatewayStrategy.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type == ApplicationEventPublisher.class);
        assertThat(hasEventPublisherField)
                .as("NicepayPaymentGatewayStrategy 에 ApplicationEventPublisher 필드가 남아 있으면 안 됨 — 이벤트 발행 경로 제거")
                .isFalse();
    }
}
