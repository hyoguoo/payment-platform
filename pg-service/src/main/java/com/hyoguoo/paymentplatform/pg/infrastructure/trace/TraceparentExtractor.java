package com.hyoguoo.paymentplatform.pg.infrastructure.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * W3C traceparent 추출·복원 헬퍼 — infrastructure/trace 계층 격리.
 *
 * <p>OTel {@code Context.current()} 에서 W3C traceparent 문자열을 추출하고,
 * 저장된 traceparent 문자열로 OTel {@link Context} 를 복원한다.
 *
 * <p>레이어 격리 규칙: OTel API import 는 이 클래스(infrastructure/trace)에만 존재한다.
 * application 계층(PgInboxPendingService, PgConfirmService 등)은 이 클래스를 의존하지 않으며,
 * 불투명 {@code String} 타입만 통과한다.
 *
 * <p>best-effort 원칙: 추출·복원 실패 시 예외를 전파하지 않고 {@code null} 또는 빈 Context 로 폴백한다.
 * 추적 연속성은 관측성 전용이며 결제 판정에 참여하지 않는다.
 */
@Slf4j
public final class TraceparentExtractor {

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final W3CTraceContextPropagator PROPAGATOR =
            W3CTraceContextPropagator.getInstance();

    private static final TextMapSetter<Map<String, String>> MAP_SETTER =
            (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.put(key, value);
                }
            };

    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    if (carrier == null) {
                        return null;
                    }
                    return carrier.get(key);
                }
            };

    private TraceparentExtractor() {}

    /**
     * 현재 OTel Context 에서 W3C traceparent 문자열을 추출한다.
     *
     * <p>활성 Span 이 없거나 INVALID 이면 {@code null} 을 반환한다 (폴백 가능).
     * 추출 실패 시 ERROR 로그를 남기고 {@code null} 로 폴백 — 예외 전파 없음.
     *
     * @return W3C traceparent 문자열 (예: "00-{32hex}-{16hex}-{2hex}"), 없으면 {@code null}
     */
    public static String extractFromCurrentContext() {
        Span currentSpan = Span.current();
        if (!currentSpan.getSpanContext().isValid()) {
            return null;
        }
        try {
            Map<String, String> carrier = new HashMap<>();
            PROPAGATOR.inject(Context.current(), carrier, MAP_SETTER);
            return carrier.get(TRACEPARENT_HEADER);
        } catch (RuntimeException e) {
            log.error("traceparent 추출 실패 — best-effort 폴백: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 저장된 W3C traceparent 문자열로 OTel {@link Context} 를 복원한다.
     *
     * <p>입력이 {@code null} 이거나 빈 문자열이면 {@link Context#root()} 를 반환한다.
     * 형식 오류 또는 복원 실패 시 best-effort 폴백으로 {@link Context#root()} 를 반환 — 예외 전파 없음.
     *
     * <p>복원된 Context 는 Remote Span 으로 부모 추적을 연결한다
     * (W3CTraceContextPropagator 가 RemoteSpan 으로 표시).
     *
     * @param traceparent W3C traceparent 문자열 (null 또는 빈 문자열 허용)
     * @return 복원된 OTel {@link Context}. 입력 오류 시 {@link Context#root()}
     */
    public static Context restoreContext(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.root();
        }
        try {
            Map<String, String> carrier = new HashMap<>();
            carrier.put(TRACEPARENT_HEADER, traceparent);
            return PROPAGATOR.extract(Context.root(), carrier, MAP_GETTER);
        } catch (RuntimeException e) {
            log.error("traceparent 복원 실패 — best-effort 폴백: traceparent={} message={}",
                    traceparent, e.getMessage());
            return Context.root();
        }
    }
}
