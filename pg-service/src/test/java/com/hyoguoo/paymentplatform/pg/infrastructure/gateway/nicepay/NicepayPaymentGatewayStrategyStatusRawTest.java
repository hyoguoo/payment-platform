package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NicepayPaymentGatewayStrategy 조회 응답의 승인 시각 원문 보존.
 *
 * <p>NicePay paidAt 은 {@code +0900}(RFC 822, 콜론 없음) 형식으로 온다 — 조회 결과에 실을 원문도
 * 승인 응답 경로(PgConfirmResult.approvedAtRaw)와 같은 방식으로 {@code +09:00} 형식으로 정규화해
 * 보존해야 오프셋 손실이 없다.
 */
@DisplayName("NicepayPaymentGatewayStrategy 조회 결과 승인 시각 원문 보존")
class NicepayPaymentGatewayStrategyStatusRawTest {

    private static final String ORDER_ID = "order-status-raw";

    private HttpOperator httpOperator;
    private NicepayPaymentGatewayStrategy strategy;

    @BeforeEach
    void setUp() {
        httpOperator = mock(HttpOperator.class);
        EncodeUtils encodeUtils = mock(EncodeUtils.class);
        when(encodeUtils.encodeBase64(anyString())).thenReturn("dummy-basic");

        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        strategy = new NicepayPaymentGatewayStrategy(
                httpOperator,
                encodeUtils,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper(),
                clock);
        ReflectionTestUtils.setField(strategy, "clientKey", "S2_dummy");
        ReflectionTestUtils.setField(strategy, "secretKey", "secret-dummy");
        ReflectionTestUtils.setField(strategy, "nicepayApiUrl", "https://sandbox-api.nicepay.co.kr");
    }

    @Test
    @DisplayName("승인응답_승인시각원문이_오프셋을_보존한다")
    void getStatusByOrderId_승인응답_승인시각원문이_오프셋을_보존한다() {
        NicepayPaymentApiResponse response = new NicepayPaymentApiResponse(
                "0000", "정상", "tid-001", ORDER_ID,
                BigDecimal.valueOf(1000), "paid", "2026-04-26T15:20:38.123+0900");
        when(httpOperator.requestGet(anyString(), anyMap(), eq(NicepayPaymentApiResponse.class)))
                .thenReturn(response);

        PgStatusResult result = strategy.getStatusByOrderId(ORDER_ID);

        assertThat(result.approvedAtRaw()).isEqualTo("2026-04-26T15:20:38.123+09:00");
    }

    @Test
    @DisplayName("승인시각_부재시_원문은_null")
    void getStatusByOrderId_승인시각_부재시_원문은_null() {
        NicepayPaymentApiResponse response = new NicepayPaymentApiResponse(
                "0000", "정상", "tid-001", ORDER_ID,
                BigDecimal.valueOf(1000), "ready", null);
        when(httpOperator.requestGet(anyString(), anyMap(), eq(NicepayPaymentApiResponse.class)))
                .thenReturn(response);

        PgStatusResult result = strategy.getStatusByOrderId(ORDER_ID);

        assertThat(result.approvedAtRaw()).isNull();
    }
}
