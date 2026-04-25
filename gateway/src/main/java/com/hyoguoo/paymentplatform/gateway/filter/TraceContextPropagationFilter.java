package com.hyoguoo.paymentplatform.gateway.filter;

import com.hyoguoo.paymentplatform.gateway.core.common.log.EventType;
import com.hyoguoo.paymentplatform.gateway.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.gateway.core.common.log.LogFmt;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * W3C Trace Context 전파 필터 (ADR-18).
 *
 * <p>유입 요청의 {@code traceparent} 헤더를 검사·검증하여 MDC에 {@code traceId}/{@code spanId}를
 * 주입한다. 헤더가 없거나 포맷이 유효하지 않으면 주입 없이 다음 필터로 패스하여
 * Micrometer Tracing이 자동 생성한 ID를 사용하도록 위임한다.
 *
 * <p>W3C traceparent 포맷: {@code 00-<trace-id>-<span-id>-<flags>}
 * <ul>
 *   <li>version: 2자리 16진수 (현재 "00")</li>
 *   <li>trace-id: 32자리 16진수 (128-bit)</li>
 *   <li>parent-id (span-id): 16자리 16진수 (64-bit)</li>
 *   <li>flags: 2자리 16진수</li>
 * </ul>
 *
 * <p>업스트림 전달 시 {@code traceparent} 헤더를 그대로 보존한다. Gateway가 WebFlux
 * 환경이므로 {@link WebFilter}/{@link ServerWebExchange}/{@link Mono} 패턴을 사용한다.
 */
@Component
public class TraceContextPropagationFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceContextPropagationFilter.class);

    static final String TRACEPARENT_HEADER = "traceparent";
    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_SPAN_ID = "spanId";

    /**
     * W3C traceparent 정규식: 00-{32hex}-{16hex}-{2hex}
     * version 필드는 "00"만 허용 (미래 버전 확장 대비 엄격 검증).
     */
    private static final Pattern TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    /** 필터 체인에서 가장 먼저 실행되도록 최상위 우선순위 부여. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceparent = exchange.getRequest().getHeaders().getFirst(TRACEPARENT_HEADER);

        Optional<TraceIds> parsed = parseTraceparent(traceparent);

        if (parsed.isPresent()) {
            TraceIds ids = parsed.get();
            MDC.put(MDC_TRACE_ID, ids.traceId());
            MDC.put(MDC_SPAN_ID, ids.spanId());
            LogFmt.debug(log, LogDomain.GATEWAY, EventType.TRACE_CONTEXT_INJECTED,
                    () -> "traceId=" + ids.traceId() + " spanId=" + ids.spanId());
        }

        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();
        LogFmt.info(log, LogDomain.GATEWAY, EventType.GATEWAY_REQUEST_RECEIVED,
                () -> "method=" + method + " path=" + path);

        return chain.filter(exchange)
                .doFinally(signal -> {
                    MDC.remove(MDC_TRACE_ID);
                    MDC.remove(MDC_SPAN_ID);
                });
    }

    /**
     * W3C traceparent 헤더 문자열을 파싱한다.
     *
     * @param traceparent 헤더 값 (null 허용)
     * @return 유효한 경우 {@link TraceIds}, 없거나 포맷 불일치 시 {@link Optional#empty()}
     */
    private Optional<TraceIds> parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent.trim().toLowerCase());
        if (!matcher.matches()) {
            LogFmt.debug(log, LogDomain.GATEWAY, EventType.TRACE_CONTEXT_MALFORMED,
                    () -> "value=" + traceparent);
            return Optional.empty();
        }

        return Optional.of(new TraceIds(matcher.group(1), matcher.group(2)));
    }

    /** traceId와 spanId를 담는 값 객체. 순수 Java record — Reactor/Servlet 의존 없음. */
    record TraceIds(String traceId, String spanId) {
    }
}
