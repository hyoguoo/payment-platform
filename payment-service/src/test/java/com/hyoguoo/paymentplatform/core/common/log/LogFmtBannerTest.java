package com.hyoguoo.paymentplatform.core.common.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * T-F3 RED — LogFmt.banner 헬퍼 단위 테스트.
 *
 * <p>LogFmt.banner(Logger, Level, String... lines) 호출 시
 * 지정한 레벨로 각 라인이 개별 로그 이벤트로 기록됨을 검증한다.
 */
@DisplayName("LogFmt.banner 헬퍼 단위 테스트 (T-F3 RED)")
class LogFmtBannerTest {

    private static final org.slf4j.Logger SLF4J_LOG =
            LoggerFactory.getLogger(LogFmtBannerTest.class);

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(LogFmtBannerTest.class);
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
    @DisplayName("banner — WARN 레벨로 2줄 호출 시 2개 WARN 이벤트 기록")
    void banner_warn_twoLines_recordsTwoWarnEvents() {
        // when
        LogFmt.banner(SLF4J_LOG, org.slf4j.event.Level.WARN, "line1", "line2");

        // then
        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).isEqualTo("line1");
        assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(1).getFormattedMessage()).isEqualTo("line2");
    }

    @Test
    @DisplayName("banner — INFO 레벨로 3줄 호출 시 3개 INFO 이벤트 기록")
    void banner_info_threeLines_recordsThreeInfoEvents() {
        // when
        LogFmt.banner(SLF4J_LOG, org.slf4j.event.Level.INFO, "A", "B", "C");

        // then
        List<ILoggingEvent> events = listAppender.list;
        assertThat(events).hasSize(3);
        events.forEach(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
    }

    @Test
    @DisplayName("banner — 빈 lines 배열 호출 시 로그 이벤트 없음")
    void banner_emptyLines_recordsNothing() {
        // when
        LogFmt.banner(SLF4J_LOG, org.slf4j.event.Level.WARN);

        // then
        assertThat(listAppender.list).isEmpty();
    }
}
