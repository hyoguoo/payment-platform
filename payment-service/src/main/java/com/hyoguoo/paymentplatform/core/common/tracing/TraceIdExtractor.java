package com.hyoguoo.paymentplatform.core.common.tracing;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * W3C traceparent 헤더에서 traceId를 추출하는 순수 Java 유틸 (ADR-18).
 *
 * <p><strong>설계 원칙</strong>:
 * <ul>
 *   <li>Reactor 타입(Mono/Flux) 미포함 — Servlet 환경(MVC + Virtual Threads) 전용.</li>
 *   <li>Spring 빈 등록 없음 — static 유틸. 호출자가 MDC 주입 책임.</li>
 *   <li>null 반환 금지 — {@link Optional} 사용.</li>
 * </ul>
 *
 * <p>W3C traceparent 포맷: {@code 00-<trace-id>-<span-id>-<flags>}
 * <ul>
 *   <li>version: 2자리 16진수 (현재 "00")</li>
 *   <li>trace-id: 32자리 16진수 (128-bit)</li>
 *   <li>parent-id (span-id): 16자리 16진수 (64-bit)</li>
 *   <li>flags: 2자리 16진수</li>
 * </ul>
 *
 * <p>사용 예시 (payment-service Servlet 필터 / 인터셉터):
 * <pre>{@code
 * String header = request.getHeader("traceparent");
 * TraceIdExtractor.extractTraceId(header)
 *     .ifPresent(traceId -> MDC.put("traceId", traceId));
 * }</pre>
 *
 * <p>Gateway 모듈은 이 클래스를 직접 참조하지 않는다 (모듈 경계 — ADR-18).
 * Gateway는 자체 WebFlux 파이프라인 안에서 동일 파싱 로직을 인라인으로 구현한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TraceIdExtractor {

    private static final Logger log = LoggerFactory.getLogger(TraceIdExtractor.class);

    /**
     * W3C traceparent 정규식: 00-{32hex}-{16hex}-{2hex}
     * version 필드는 "00"만 허용 (미래 버전 확장 대비 엄격 검증).
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    /**
     * W3C {@code traceparent} 헤더에서 traceId(128-bit, 32자리 16진수)를 추출한다.
     *
     * @param traceparentHeader {@code traceparent} 헤더 값. null 또는 blank 허용.
     * @return 유효한 traceparent면 traceId 문자열, 없거나 포맷 불일치 시 {@link Optional#empty()}
     */
    public static Optional<String> extractTraceId(String traceparentHeader) {
        return parse(traceparentHeader).map(TraceComponents::traceId);
    }

    /**
     * W3C {@code traceparent} 헤더에서 spanId(64-bit, 16자리 16진수)를 추출한다.
     *
     * @param traceparentHeader {@code traceparent} 헤더 값. null 또는 blank 허용.
     * @return 유효한 traceparent면 spanId 문자열, 없거나 포맷 불일치 시 {@link Optional#empty()}
     */
    public static Optional<String> extractSpanId(String traceparentHeader) {
        return parse(traceparentHeader).map(TraceComponents::spanId);
    }

    /**
     * W3C {@code traceparent} 헤더 전체를 파싱하여 구성 요소를 반환한다.
     *
     * @param traceparentHeader {@code traceparent} 헤더 값. null 또는 blank 허용.
     * @return 파싱 성공 시 {@link TraceComponents}, 실패 시 {@link Optional#empty()}
     */
    public static Optional<TraceComponents> parse(String traceparentHeader) {
        if (traceparentHeader == null || traceparentHeader.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparentHeader.trim().toLowerCase());
        if (!matcher.matches()) {
            log.debug("traceparent 포맷 불일치: value={}", traceparentHeader);
            return Optional.empty();
        }

        return Optional.of(new TraceComponents(matcher.group(1), matcher.group(2), matcher.group(3)));
    }

    /**
     * traceparent 파싱 결과 값 객체.
     * 순수 Java record — Reactor/Servlet 의존 없음.
     *
     * @param traceId 32자리 16진수 trace-id
     * @param spanId  16자리 16진수 parent-id(span-id)
     * @param flags   2자리 16진수 trace-flags
     */
    public record TraceComponents(String traceId, String spanId, String flags) {

        /** 샘플링 여부. flags 최하위 비트가 1이면 sampled. */
        public boolean isSampled() {
            return (Integer.parseInt(flags, 16) & 0x01) == 1;
        }
    }
}
