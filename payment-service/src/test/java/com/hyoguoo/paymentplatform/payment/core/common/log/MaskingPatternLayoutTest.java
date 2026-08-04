package com.hyoguoo.paymentplatform.payment.core.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * MaskingPatternLayout 단위 테스트.
 *
 * <p>패턴은 코드가 아니라 레이아웃에 등록한 정규식으로만 관리된다 — 각 정규식은 캡처 그룹을
 * 하나 가지며, 그 그룹으로 잡힌 부분만 가려지고 나머지(접두사 등)는 그대로 남는다.
 */
@DisplayName("MaskingPatternLayout 단위 테스트")
class MaskingPatternLayoutTest {

    private static final String PAYMENT_KEY_PATTERN = "paymentKey=[A-Za-z0-9]{4}([A-Za-z0-9_-]{4,})";
    private static final String AUTHORIZATION_PATTERN =
            "(?i)Authorization:\\s*Bearer\\s+[A-Za-z0-9._-]{4}([A-Za-z0-9._-]{4,})";
    private static final String EMAIL_PATTERN = "[A-Za-z0-9._%+-]{2}([A-Za-z0-9._%+-]*)@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";
    private static final String CARD_NUMBER_PATTERN = "(?:\\d{4})[- ]?(\\d{4}[- ]?\\d{4}[- ]?\\d{4})";

    private static final String FULL_LOG_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [traceId:%X{traceId:-N/A}] %-5level %logger{36} - %msg%n";

    private LoggerContext context;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logbackLogger = context.getLogger(MaskingPatternLayoutTest.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logbackLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(listAppender);
        listAppender.stop();
        MDC.clear();
    }

    private ILoggingEvent captureEvent(String message) {
        logbackLogger.info(message);
        List<ILoggingEvent> events = listAppender.list;
        return events.get(events.size() - 1);
    }

    private MaskingPatternLayout newLayout(String pattern, String... maskRegexes) {
        MaskingPatternLayout layout = new MaskingPatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        for (String regex : maskRegexes) {
            layout.addMaskPattern(regex);
        }
        layout.start();
        return layout;
    }

    @Test
    @DisplayName("결제 키가 앞자리만 남고 가려진다")
    void 결제_키가_앞자리만_남고_가려진다() {
        MaskingPatternLayout layout = newLayout("%msg", PAYMENT_KEY_PATTERN);
        ILoggingEvent event = captureEvent("paymentKey=tviv20240909abcXYZ123456 orderId=ORD-1");

        String result = layout.doLayout(event);

        assertThat(result).startsWith("paymentKey=tviv");
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("20240909abcXYZ123456");
        assertThat(result).contains("orderId=ORD-1");
    }

    @Test
    @DisplayName("인증 헤더 값이 가려진다")
    void 인증_헤더_값이_가려진다() {
        MaskingPatternLayout layout = newLayout("%msg", AUTHORIZATION_PATTERN);
        ILoggingEvent event = captureEvent("Authorization: Bearer abcd1234efgh5678ijkl9012");

        String result = layout.doLayout(event);

        assertThat(result).startsWith("Authorization: Bearer abcd");
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("1234efgh5678ijkl9012");
    }

    @Test
    @DisplayName("이메일 주소가 가려진다")
    void 이메일_주소가_가려진다() {
        MaskingPatternLayout layout = newLayout("%msg", EMAIL_PATTERN);
        ILoggingEvent event = captureEvent("email=hyoguoo1234@example.com");

        String result = layout.doLayout(event);

        assertThat(result).startsWith("email=hy");
        assertThat(result).contains("***");
        assertThat(result).contains("@example.com");
        assertThat(result).doesNotContain("guoo1234@example.com");
    }

    @Test
    @DisplayName("카드번호 형태 문자열이 가려진다")
    void 카드번호_형태_문자열이_가려진다() {
        MaskingPatternLayout layout = newLayout("%msg", CARD_NUMBER_PATTERN);
        ILoggingEvent event = captureEvent("cardNumber=1234-5678-9012-3456");

        String result = layout.doLayout(event);

        assertThat(result).startsWith("cardNumber=1234");
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("5678-9012-3456");
    }

    @Test
    @DisplayName("민감하지 않은 문자열은 그대로 통과한다")
    void 민감하지_않은_문자열은_그대로_통과한다() {
        MaskingPatternLayout layout = newLayout(
                "%msg",
                PAYMENT_KEY_PATTERN, AUTHORIZATION_PATTERN, EMAIL_PATTERN, CARD_NUMBER_PATTERN
        );
        String message = "orderId=ORD-1234 status=DONE amount=1000";
        ILoggingEvent event = captureEvent(message);

        String result = layout.doLayout(event);

        assertThat(result).isEqualTo(message);
    }

    @Test
    @DisplayName("패턴이 없으면 원문이 그대로 나온다")
    void 패턴이_없으면_원문이_그대로_나온다() {
        MaskingPatternLayout layout = newLayout("%msg");
        String message = "paymentKey=tviv20240909abcXYZ123456";
        ILoggingEvent event = captureEvent(message);

        String result = layout.doLayout(event);

        assertThat(result).isEqualTo(message);
    }

    @Test
    @DisplayName("기존 로그 포맷(시각, 스레드, 추적 번호, 레벨)이 그대로 유지된다")
    void 기존_로그_포맷이_그대로_유지된다() {
        MDC.put("traceId", "trace-abc-123");
        MaskingPatternLayout layout = newLayout(
                FULL_LOG_PATTERN,
                PAYMENT_KEY_PATTERN, AUTHORIZATION_PATTERN, EMAIL_PATTERN, CARD_NUMBER_PATTERN
        );
        ILoggingEvent event = captureEvent("paymentKey=tviv20240909abcXYZ123456");

        String result = layout.doLayout(event);

        assertThat(result).contains("[traceId:trace-abc-123]");
        assertThat(result).contains("[" + Thread.currentThread().getName() + "]");
        assertThat(result).contains("INFO ");
        assertThat(result).contains("paymentKey=tviv");
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("20240909abcXYZ123456");
    }
}
