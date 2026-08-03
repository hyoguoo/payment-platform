package com.hyoguoo.paymentplatform.pg.mock;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 인메모리 SpanExporter Fake — {@code SimpleSpanProcessor} 와 조합해 실제로 기록된 span 을
 * 리스트로 노출한다. OTel SDK 는 이미 빌드 의존성에 있고(opentelemetry-exporter-otlp 전이 의존)
 * 공식 테스트 아티팩트(opentelemetry-sdk-testing)만 없으므로, {@link SpanExporter} 계약을
 * 직접 구현해 새 의존 추가 없이 같은 검증 능력을 확보한다.
 */
public class FakeSpanExporter implements SpanExporter {

    private final List<SpanData> exportedSpans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        exportedSpans.addAll(spans);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> getExportedSpans() {
        return Collections.unmodifiableList(exportedSpans);
    }
}
