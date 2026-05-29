package com.hyoguoo.paymentplatform.pg.infrastructure.trace;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceparentExtractor 단위 테스트.
 *
 * <p>OTel SDK 를 직접 사용해 유효한 Span 을 활성화하고, W3C traceparent 추출·복원 동작을 검증한다.
 * infrastructure 계층 격리 검증: OTel API 의존은 이 계층 안에만 존재한다.
 */
@DisplayName("TraceparentExtractor — W3C traceparent 추출·복원 헬퍼")
class TraceparentExtractorTest {

    private static final String W3C_TRACEPARENT_PATTERN =
            "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$";

    private Tracer tracer;

    @BeforeEach
    void setUp() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        tracer = sdk.getTracer("test");
    }

    @Test
    @DisplayName("extract_활성컨텍스트_유효traceparent반환")
    void extract_활성컨텍스트_유효traceparent반환() {
        // given: OTel Context에 유효 Span 활성화
        Span span = tracer.spanBuilder("test-span").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            // when
            String traceparent = TraceparentExtractor.extractFromCurrentContext();

            // then
            assertThat(traceparent).isNotNull();
            assertThat(traceparent).matches(W3C_TRACEPARENT_PATTERN);
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("extract_컨텍스트없음_null반환")
    void extract_컨텍스트없음_null반환() {
        // given: 기본 INVALID span (no active span)
        // when
        String traceparent = TraceparentExtractor.extractFromCurrentContext();

        // then: INVALID span이므로 null 반환 (폴백 가능)
        assertThat(traceparent).isNull();
    }

    @Test
    @DisplayName("restore_유효traceparent_컨텍스트복원")
    void restore_유효traceparent_컨텍스트복원() {
        // given: 유효 traceparent 문자열을 Span 활성화 후 추출
        Span span = tracer.spanBuilder("restore-test-span").startSpan();
        String traceparent;
        try (Scope ignored = span.makeCurrent()) {
            traceparent = TraceparentExtractor.extractFromCurrentContext();
        } finally {
            span.end();
        }
        assertThat(traceparent).isNotNull();

        // given: traceId 파싱 (traceparent = "00-{32hex}-{16hex}-{2hex}")
        String[] parts = traceparent.split("-");
        String expectedTraceId = parts[1];

        // when
        Context restoredCtx = TraceparentExtractor.restoreContext(traceparent);

        // then
        assertThat(restoredCtx).isNotNull();
        // 복원된 컨텍스트에서 Span.fromContext로 trace-id 검증
        Span restoredSpan = Span.fromContext(restoredCtx);
        String restoredTraceId = restoredSpan.getSpanContext().getTraceId();
        assertThat(restoredTraceId).isEqualTo(expectedTraceId);
    }

    @Test
    @DisplayName("restore_null입력_empty컨텍스트반환")
    void restore_null입력_empty컨텍스트반환() {
        // when: null 입력
        Context ctx = TraceparentExtractor.restoreContext(null);

        // then: 예외 없음, Context.root()와 동등한 빈 컨텍스트 반환
        assertThat(ctx).isNotNull();
        // 복원된 컨텍스트의 Span은 INVALID여야 함
        Span span = Span.fromContext(ctx);
        assertThat(span.getSpanContext().isValid()).isFalse();
    }

    @Test
    @DisplayName("restore_형식오류_예외없이null반환")
    void restore_형식오류_예외없이null반환() {
        // when: 잘못된 형식 문자열
        Context ctx = TraceparentExtractor.restoreContext("invalid-format");

        // then: 예외 없음, best-effort 폴백 (빈 컨텍스트 또는 root 반환)
        assertThat(ctx).isNotNull();
    }
}
