package com.hyoguoo.paymentplatform.product.core.common.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.event.Level;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LogFmt {

    private static final String INFO_LOG_FORMAT = "[{}] | {} | {}";
    private static final String INFO_LOG_FORMAT_NO_MESSAGE = "[{}] | {}";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String toJson(Object object) {
        if (Objects.isNull(object)) {
            return "null";
        }

        try {
            return normalizeSpace(objectMapper.writeValueAsString(object));
        } catch (JsonProcessingException e) {
            return "Error converting to JSON";
        }
    }

    private static String normalizeSpace(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.trim().replaceAll("\\s+", " ");
    }

    public static void info(Logger logger, LogDomain logDomain, EventType event) {
        if (logger.isInfoEnabled()) {
            logger.info(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name(), event.name());
        }
    }

    public static void info(Logger logger, LogDomain logDomain, EventType event, Supplier<String> messageSupplier) {
        if (logger.isInfoEnabled()) {
            logger.info(INFO_LOG_FORMAT, logDomain.name(), event.name(), messageSupplier.get());
        }
    }

    public static void warn(Logger logger, LogDomain logDomain, EventType event) {
        if (logger.isWarnEnabled()) {
            logger.warn(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name(), event.name());
        }
    }

    public static void warn(Logger logger, LogDomain logDomain, EventType event, Supplier<String> messageSupplier) {
        if (logger.isWarnEnabled()) {
            logger.warn(INFO_LOG_FORMAT, logDomain.name(), event.name(), messageSupplier.get());
        }
    }

    public static void error(Logger logger, LogDomain logDomain, EventType event) {
        if (logger.isErrorEnabled()) {
            logger.error(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name(), event.name());
        }
    }

    public static void error(Logger logger, LogDomain logDomain, EventType event, Supplier<String> messageSupplier) {
        if (logger.isErrorEnabled()) {
            logger.error(INFO_LOG_FORMAT, logDomain.name(), event.name(), messageSupplier.get());
        }
    }

    public static void debug(Logger logger, LogDomain logDomain, EventType event) {
        if (logger.isDebugEnabled()) {
            logger.debug(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name(), event.name());
        }
    }

    public static void debug(Logger logger, LogDomain logDomain, EventType event, Supplier<String> messageSupplier) {
        if (logger.isDebugEnabled()) {
            logger.debug(INFO_LOG_FORMAT, logDomain.name(), event.name(), messageSupplier.get());
        }
    }

    /**
     * 기동 배너(시각적 경고)를 지정 레벨로 출력한다.
     *
     * <p>CONVENTIONS: 기동 배너는 반드시 이 메서드로만 허용한다.
     * 직접 {@code log.warn("╔...")} 호출은 금지 — LogFmt 경유 필수.
     *
     * @param logger 출력 대상 Logger
     * @param level  출력 레벨 (일반적으로 {@link Level#WARN})
     * @param lines  배너 라인 배열
     */
    public static void banner(Logger logger, Level level, String... lines) {
        for (String line : lines) {
            switch (level) {
                case WARN -> { if (logger.isWarnEnabled()) logger.warn(line); }
                case INFO -> { if (logger.isInfoEnabled()) logger.info(line); }
                case ERROR -> { if (logger.isErrorEnabled()) logger.error(line); }
                case DEBUG -> { if (logger.isDebugEnabled()) logger.debug(line); }
                case TRACE -> { if (logger.isTraceEnabled()) logger.trace(line); }
            }
        }
    }
}
