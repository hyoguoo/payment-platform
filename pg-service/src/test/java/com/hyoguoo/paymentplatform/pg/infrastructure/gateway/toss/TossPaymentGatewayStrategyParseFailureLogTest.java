package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
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
 * 벤더 에러 응답 파싱 실패 시 로그에 남는 원문 길이 제한.
 *
 * <p>벤더가 계약과 다른 형식의 본문(파싱 불가한 JSON)을 보내면 원문을 그대로 로그에 남기던 자리 —
 * 이 시스템에서 예상 못한 외부 본문이 로그로 흐르는 유일한 자리라 상한을 둔다. 반환되는 실패 응답
 * 자체(UNKNOWN 처리)는 이 제한과 무관하게 종전과 동일하게 원문을 그대로 담는다.
 */
@DisplayName("TossPaymentGatewayStrategy 에러 응답 파싱 실패 로그 길이 제한")
class TossPaymentGatewayStrategyParseFailureLogTest {

    private static final String ORDER_ID = "order-parse-failure";

    private HttpOperator httpOperator;
    private TossPaymentGatewayStrategy strategy;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger strategyLogger;

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

        strategyLogger = (Logger) LoggerFactory.getLogger(TossPaymentGatewayStrategy.class);
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
        when(httpOperator.requestGet(anyString(), anyMap(), eq(TossPaymentApiResponse.class)))
                .thenThrow(exception);

        assertThatThrownBy(() -> strategy.getStatusByOrderId(ORDER_ID))
                .isInstanceOf(PgGatewayRetryableException.class);
    }

    private String capturedParseFailureLogMessage() {
        List<ILoggingEvent> warnLogs = logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warnLogs).hasSize(1);
        return warnLogs.get(0).getFormattedMessage();
    }

    @Test
    @DisplayName("파싱_실패_로그의_원문은_상한_길이를_넘지_않는다")
    void 파싱_실패_로그의_원문은_상한_길이를_넘지_않는다() {
        String hugeMalformedBody = "{".repeat(5_000);

        triggerParseFailure(hugeMalformedBody);

        int maxLength = (int) ReflectionTestUtils.getField(TossPaymentGatewayStrategy.class, "MAX_PARSE_FAILURE_LOG_LENGTH");
        String loggedRaw = capturedParseFailureLogMessage().split("raw=", 2)[1];

        assertThat(loggedRaw.length())
                .as("잘림 표시를 포함해도 로그에 원문이 무한정 늘어나면 안 된다")
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
                .as("잘린 것과 원래 짧은 것을 구분할 수 있어야 한다")
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
                .as("상한보다 짧은 원문은 잘림 표시 없이 그대로 남아야 한다")
                .contains("raw=" + shortMalformedBody)
                .doesNotContain("truncated");
    }
}
