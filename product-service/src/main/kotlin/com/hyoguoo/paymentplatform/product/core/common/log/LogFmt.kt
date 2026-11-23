package com.hyoguoo.paymentplatform.product.core.common.log

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * 로그 한 줄을 "[도메인] | 이벤트 | 메시지" 형식으로 남긴다.
 *
 * 메시지는 함수로 받아 해당 레벨이 켜져 있을 때만 만든다.
 */
@Suppress("TooManyFunctions") // 레벨별(info·warn·error·debug)로 메시지 유무 두 가지씩 둔다
object LogFmt {
    private const val INFO_LOG_FORMAT = "[{}] | {} | {}"
    private const val INFO_LOG_FORMAT_NO_MESSAGE = "[{}] | {}"
    private val WHITESPACE = Regex("\\s+")

    private val objectMapper: ObjectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Suppress("SwallowedException") // 로그 본문을 만드는 중 실패해도 로깅 자체는 이어가도록 대체 문구를 돌려준다
    fun toJson(value: Any?): String {
        if (value == null) {
            return "null"
        }
        return try {
            normalizeSpace(objectMapper.writeValueAsString(value))
        } catch (e: JsonProcessingException) {
            "Error converting to JSON"
        }
    }

    private fun normalizeSpace(str: String): String = if (str.isEmpty()) str else str.trim().replace(WHITESPACE, " ")

    fun info(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
    ) {
        if (logger.isInfoEnabled) {
            logger.info(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name, event.name)
        }
    }

    fun info(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
        message: () -> String?,
    ) {
        if (logger.isInfoEnabled) {
            logger.info(INFO_LOG_FORMAT, logDomain.name, event.name, message())
        }
    }

    fun warn(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
    ) {
        if (logger.isWarnEnabled) {
            logger.warn(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name, event.name)
        }
    }

    fun warn(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
        message: () -> String?,
    ) {
        if (logger.isWarnEnabled) {
            logger.warn(INFO_LOG_FORMAT, logDomain.name, event.name, message())
        }
    }

    fun error(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
    ) {
        if (logger.isErrorEnabled) {
            logger.error(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name, event.name)
        }
    }

    fun error(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
        message: () -> String?,
    ) {
        if (logger.isErrorEnabled) {
            logger.error(INFO_LOG_FORMAT, logDomain.name, event.name, message())
        }
    }

    fun debug(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
    ) {
        if (logger.isDebugEnabled) {
            logger.debug(INFO_LOG_FORMAT_NO_MESSAGE, logDomain.name, event.name)
        }
    }

    fun debug(
        logger: Logger,
        logDomain: LogDomain,
        event: EventType,
        message: () -> String?,
    ) {
        if (logger.isDebugEnabled) {
            logger.debug(INFO_LOG_FORMAT, logDomain.name, event.name, message())
        }
    }

    /**
     * 기동 배너(시각적 경고)를 지정 레벨로 출력한다.
     *
     * CONVENTIONS: 기동 배너는 반드시 이 함수로만 허용한다. 직접 `log.warn("╔...")` 호출은 금지 — LogFmt 경유 필수.
     */
    fun banner(
        logger: Logger,
        level: Level,
        vararg lines: String,
    ) {
        for (line in lines) {
            when (level) {
                Level.WARN -> if (logger.isWarnEnabled) logger.warn(line)
                Level.INFO -> if (logger.isInfoEnabled) logger.info(line)
                Level.ERROR -> if (logger.isErrorEnabled) logger.error(line)
                Level.DEBUG -> if (logger.isDebugEnabled) logger.debug(line)
                Level.TRACE -> if (logger.isTraceEnabled) logger.trace(line)
            }
        }
    }
}
