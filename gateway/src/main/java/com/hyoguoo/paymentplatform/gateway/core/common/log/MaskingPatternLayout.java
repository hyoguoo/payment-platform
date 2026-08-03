package com.hyoguoo.paymentplatform.gateway.core.common.log;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 한 줄이 최종 문자열로 조립된 직후, 정규식으로 민감 값을 가리는 레이아웃.
 *
 * <p>패턴은 개별 로깅 코드가 아니라 logback 설정의 {@code <maskPattern>} 으로 등록한다 —
 * 코드를 고치지 않고 패턴을 추가·제거할 수 있다. 각 패턴은 캡처 그룹을 정확히 하나 가져야
 * 하며, 그 그룹으로 잡힌 부분만 가려진다. 그룹 밖 문자(접두사 등)는 그대로 남아 디버깅에
 * 필요한 식별 단서를 유지한다.
 */
public class MaskingPatternLayout extends PatternLayout {

    private static final String MASK = "***";

    private final List<Pattern> maskPatterns = new ArrayList<>();

    public void addMaskPattern(String regex) {
        maskPatterns.add(Pattern.compile(regex));
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        return mask(super.doLayout(event));
    }

    private String mask(String message) {
        String result = message;
        for (Pattern pattern : maskPatterns) {
            result = maskByPattern(result, pattern);
        }
        return result;
    }

    private String maskByPattern(String message, Pattern pattern) {
        Matcher matcher = pattern.matcher(message);
        StringBuilder builder = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            builder.append(message, lastEnd, matcher.start(1));
            builder.append(MASK);
            lastEnd = matcher.end(1);
        }
        builder.append(message.substring(lastEnd));
        return builder.toString();
    }
}
