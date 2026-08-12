package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
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
 * TossPaymentGatewayStrategy 조회 응답의 승인 시각 원문 보존 — 좀비 회수/중복 승인 흡수 경로가
 * 오프셋 없는 LocalDateTime 대신 원문을 그대로 실을 수 있어야 정산일이 밀리지 않는다.
 */
@DisplayName("TossPaymentGatewayStrategy 조회 결과 승인 시각 원문 보존")
class TossPaymentGatewayStrategyStatusRawTest {

    private static final String ORDER_ID = "order-status-raw";

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
    @DisplayName("승인응답_승인시각원문이_오프셋을_보존한다")
    void getStatusByOrderId_승인응답_승인시각원문이_오프셋을_보존한다() {
        String rawApprovedAt = "2026-04-26T15:20:38+09:00";
        TossPaymentApiResponse response = new TossPaymentApiResponse(
                "pk-001", ORDER_ID, 1000.0, "DONE", rawApprovedAt, null);
        when(httpOperator.requestGet(anyString(), anyMap(), eq(TossPaymentApiResponse.class)))
                .thenReturn(response);

        PgStatusResult result = strategy.getStatusByOrderId(ORDER_ID);

        assertThat(result.approvedAtRaw()).isEqualTo(rawApprovedAt);
    }

    @Test
    @DisplayName("승인시각_부재시_원문은_null")
    void getStatusByOrderId_승인시각_부재시_원문은_null() {
        TossPaymentApiResponse response = new TossPaymentApiResponse(
                "pk-001", ORDER_ID, 1000.0, "READY", null, null);
        when(httpOperator.requestGet(anyString(), anyMap(), eq(TossPaymentApiResponse.class)))
                .thenReturn(response);

        PgStatusResult result = strategy.getStatusByOrderId(ORDER_ID);

        assertThat(result.approvedAtRaw()).isNull();
    }
}
