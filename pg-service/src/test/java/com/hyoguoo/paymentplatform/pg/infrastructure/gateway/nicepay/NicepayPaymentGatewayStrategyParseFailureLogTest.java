package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NicePay 도 Toss 와 같은 형태로 벤더 에러 응답 파싱 실패 시 원문을 로그에 남긴다 —
 * 같은 상한 제약을 검증한다. 상세 동작(짧은 원문 보존 등) 근거는 Toss 쪽 테스트가 정본이고,
 * 여기서는 같은 자리에 같은 제한이 걸려 있는지만 확인한다.
 */
@DisplayName("NicepayPaymentGatewayStrategy 에러 응답 파싱 실패 로그 길이 제한")
class NicepayPaymentGatewayStrategyParseFailureLogTest {

    private static final String ORDER_ID = "order-parse-failure";

    private HttpOperator httpOperator;
    private NicepayPaymentGatewayStrategy strategy;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger strategyLogger;

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

        strategyLogger = (Logger) LoggerFactory.getLogger(NicepayPaymentGatewayStrategy.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        strategyLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        strategyLogger.detachAppender(logAppender);
    }

    private void triggerParseFailure(String malformedBody) {
        RestClientResponseException exception = new RestClientResponseException(
                "Bad Request", 400, "Bad Request", new HttpHeaders(),
                malformedBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        when(httpOperator.requestGet(anyString(), anyMap(), eq(NicepayPaymentApiResponse.class)))
                .thenThrow(exception);

        assertThatThrownBy(() -> strategy.getStatusByOrderId(ORDER_ID))
                .isInstanceOf(PgGatewayNonRetryableException.class);
    }

    private String capturedParseFailureLogMessage() {
        List<ILoggingEvent> warnLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnLogs).hasSize(1);
        return warnLogs.get(0).getFormattedMessage();
    }

    private int readMaxParseFailureLogLength() {
        Object rawValue = ReflectionTestUtils.getField(NicepayPaymentGatewayStrategy.class, "MAX_PARSE_FAILURE_LOG_LENGTH");

        assertThat(rawValue)
                .as("MAX_PARSE_FAILURE_LOG_LENGTH 필드를 찾을 수 없다 — 필드명이 바뀌었는지 확인한다")
                .isNotNull();

        return (int) rawValue;
    }

    @Test
    @DisplayName("파싱_실패_로그의_원문은_상한_길이를_넘지_않는다")
    void 파싱_실패_로그의_원문은_상한_길이를_넘지_않는다() {
        String hugeMalformedBody = "{".repeat(5_000);

        triggerParseFailure(hugeMalformedBody);

        int maxLength = readMaxParseFailureLogLength();
        String loggedRaw = capturedParseFailureLogMessage().split("raw=", 2)[1];

        assertThat(loggedRaw.length())
                .isLessThan(hugeMalformedBody.length())
                .isLessThanOrEqualTo(maxLength + 50);
    }

    @Test
    @DisplayName("상한을_넘으면_잘렸음이_표시된다")
    void 상한을_넘으면_잘렸음이_표시된다() {
        String hugeMalformedBody = "{".repeat(5_000);

        triggerParseFailure(hugeMalformedBody);

        String loggedMessage = capturedParseFailureLogMessage();

        assertThat(loggedMessage)
                .contains("truncated")
                .contains("originalLength=" + hugeMalformedBody.length());
    }

    @Test
    @DisplayName("짧은_원문은_그대로_남는다")
    void 짧은_원문은_그대로_남는다() {
        String shortMalformedBody = "not-json";

        triggerParseFailure(shortMalformedBody);

        String loggedMessage = capturedParseFailureLogMessage();

        assertThat(loggedMessage)
                .contains("raw=" + shortMalformedBody)
                .doesNotContain("truncated");
    }
}
