package com.hyoguoo.paymentplatform.pg.core.common.log;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;

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
}
