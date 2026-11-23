package com.hyoguoo.paymentplatform.product.core.common.log

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * 로그 한 줄이 최종 문자열로 조립된 직후, 정규식으로 민감 값을 가리는 레이아웃.
 *
 * 패턴은 개별 로깅 코드가 아니라 logback 설정의 `<maskPattern>` 으로 등록한다 —
 * 코드를 고치지 않고 패턴을 추가·제거할 수 있다. 각 패턴은 캡처 그룹을 정확히 하나 가져야
 * 하며, 그 그룹으로 잡힌 부분만 가려진다. 그룹 밖 문자(접두사 등)는 그대로 남아 디버깅에
 * 필요한 식별 단서를 유지한다.
 */
class MaskingPatternLayout : PatternLayout() {
    private val maskPatterns = mutableListOf<Regex>()

    /** logback 설정의 `<maskPattern>` 요소마다 불린다. */
    fun addMaskPattern(regex: String) {
        maskPatterns.add(Regex(regex))
    }

    override fun doLayout(event: ILoggingEvent): String =
        maskPatterns.fold(super.doLayout(event)) { message, pattern -> maskByPattern(message, pattern) }

    private fun maskByPattern(
        message: String,
        pattern: Regex,
    ): String =
        buildString {
            var lastEnd = 0
            for (match in pattern.findAll(message)) {
                val masked = checkNotNull(match.groups[1]) { "maskPattern 은 캡처 그룹을 하나 가져야 한다: $pattern" }
                append(message, lastEnd, masked.range.first)
                append(MASK)
                lastEnd = masked.range.last + 1
            }
            append(message, lastEnd, message.length)
        }

    private companion object {
        const val MASK = "***"
    }
}
