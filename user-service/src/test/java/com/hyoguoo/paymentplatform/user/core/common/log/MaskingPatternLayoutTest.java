package com.hyoguoo.paymentplatform.user.core.common.log;

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

/**
 * MaskingPatternLayout 확인 테스트 — 결제 키 형태 문자열이 실제로 가려지는지만 확인한다.
 * 패턴별 상세 동작은 payment-service의 MaskingPatternLayoutTest가 정본이다.
 */
@DisplayName("MaskingPatternLayout 확인 테스트")
class MaskingPatternLayoutTest {

    private static final String PAYMENT_KEY_PATTERN = "paymentKey=[A-Za-z0-9]{4}([A-Za-z0-9_-]{4,})";

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
    }

    @Test
    @DisplayName("결제 키가 앞자리만 남고 가려진다")
    void 결제_키가_앞자리만_남고_가려진다() {
        MaskingPatternLayout layout = new MaskingPatternLayout();
        layout.setContext(context);
        layout.setPattern("%msg");
        layout.addMaskPattern(PAYMENT_KEY_PATTERN);
        layout.start();

        logbackLogger.info("paymentKey=tviv20240909abcXYZ123456 orderId=ORD-1");
        List<ILoggingEvent> events = listAppender.list;
        ILoggingEvent event = events.get(events.size() - 1);

        String result = layout.doLayout(event);

        assertThat(result).startsWith("paymentKey=tviv");
        assertThat(result).contains("***");
        assertThat(result).doesNotContain("20240909abcXYZ123456");
        assertThat(result).contains("orderId=ORD-1");
    }
}
