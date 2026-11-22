package com.hyoguoo.paymentplatform.product.core.common.log

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * MaskingPatternLayout 확인 테스트 — 결제 키 형태 문자열이 실제로 가려지는지만 확인한다.
 * 패턴별 상세 동작은 payment-service의 MaskingPatternLayoutTest가 정본이다.
 */
@DisplayName("MaskingPatternLayout 확인 테스트")
class MaskingPatternLayoutTest {
    private lateinit var context: LoggerContext
    private lateinit var logbackLogger: Logger
    private lateinit var listAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        context = LoggerFactory.getILoggerFactory() as LoggerContext
        logbackLogger = context.getLogger(MaskingPatternLayoutTest::class.java)
        listAppender = ListAppender()
        listAppender.start()
        logbackLogger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logbackLogger.detachAppender(listAppender)
        listAppender.stop()
    }

    @Test
    @DisplayName("결제 키가 앞자리만 남고 가려진다")
    fun `결제_키가_앞자리만_남고_가려진다`() {
        val layout = MaskingPatternLayout()
        layout.context = context
        layout.pattern = "%msg"
        layout.addMaskPattern(PAYMENT_KEY_PATTERN)
        layout.start()

        logbackLogger.info("paymentKey=tviv20240909abcXYZ123456 orderId=ORD-1")
        val events = listAppender.list
        val event = events[events.size - 1]

        val result = layout.doLayout(event)

        assertThat(result).startsWith("paymentKey=tviv")
        assertThat(result).contains("***")
        assertThat(result).doesNotContain("20240909abcXYZ123456")
        assertThat(result).contains("orderId=ORD-1")
    }

    private companion object {
        const val PAYMENT_KEY_PATTERN = "paymentKey=[A-Za-z0-9]{4}([A-Za-z0-9_-]{4,})"
    }
}
