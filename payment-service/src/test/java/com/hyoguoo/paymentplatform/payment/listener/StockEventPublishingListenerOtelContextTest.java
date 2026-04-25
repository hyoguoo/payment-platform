package com.hyoguoo.paymentplatform.payment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T-I7 RED — StockEventPublishingListener AFTER_COMMIT OTel Context 전파 검증.
 *
 * <p>검증 목표:
 * <ul>
 *   <li>StockCommitRequestedEvent 에 포함된 otelContext 가 onStockCommitRequested 내부에서
 *       활성화되어 publish 호출 시점에 Span.current().getSpanContext().getTraceId() 가
 *       producer 측 캡처 traceId 와 일치해야 한다.</li>
 *   <li>StockRestoreRequestedEvent 에 포함된 otelContext 가 onStockRestoreRequested 내부에서
 *       활성화되어 동일하게 검증된다.</li>
 *   <li>리스너 종료 후 Context 가 이전 상태(root)로 복원되어야 한다.</li>
 * </ul>
 *
 * <p>시뮬레이션:
 * <ol>
 *   <li>producer 측: 가짜 SpanContext(traceId="aabbccddeeff00112233445566778899")를 포함한
 *       OTel Span 을 현재 Context 에 주입한다. Context.current() 를 event 에 포함.</li>
 *   <li>listener 측: event.otelContext().makeCurrent() 로 OTel Context 복원 후 publish 호출.</li>
 *   <li>publish 호출 시점 Span.current().getSpanContext().getTraceId() 를 캡처하여 검증.</li>
 *   <li>리스너 종료 후 Context 에 active span 이 없음을 검증.</li>
 * </ol>
 */
@DisplayName("StockEventPublishingListener — T-I7 AFTER_COMMIT OTel Context 전파 검증")
class StockEventPublishingListenerOtelContextTest {

    private static final String EXPECTED_TRACE_ID = "aabbccddeeff00112233445566778899";
    private static final String EXPECTED_SPAN_ID = "0011223344556677";

    private StockCommitEventPublisherPort stockCommitPublisher;
    private StockRestoreEventPublisherPort stockRestorePublisher;
    private SimpleMeterRegistry meterRegistry;
    private StockEventPublishingListener sut;

    @BeforeEach
    void setUp() {
        stockCommitPublisher = Mockito.mock(StockCommitEventPublisherPort.class);
        stockRestorePublisher = Mockito.mock(StockRestoreEventPublisherPort.class);
        meterRegistry = new SimpleMeterRegistry();
        sut = new StockEventPublishingListener(stockCommitPublisher, stockRestorePublisher, meterRegistry);
    }

    // -----------------------------------------------------------------------
    // TC-I7-1: onStockCommitRequested — publish 시점에 producer 측 OTel Context 활성화 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitRequested — publish 시점에 producer 측 OTel traceId가 활성화된다")
    void onStockCommitRequested_shouldActivateOtelContextDuringPublish() {
        // given: producer 측 OTel Span 포함 Context 캡처
        SpanContext spanContext = SpanContext.create(
                EXPECTED_TRACE_ID, EXPECTED_SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault()
        );
        Span producerSpan = Span.wrap(spanContext);
        Context producerOtelContext = Context.current().with(producerSpan);
        ContextSnapshot emptySnapshot = ContextSnapshotFactory.builder().build().captureAll();

        AtomicReference<String> traceIdAtPublish = new AtomicReference<>();
        doAnswer(invocation -> {
            // publish 호출 시점 OTel active span traceId 캡처
            traceIdAtPublish.set(Span.current().getSpanContext().getTraceId());
            return null;
        }).when(stockCommitPublisher).publish(any(), any(Integer.class), any());

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-i7-commit-001", "order-i7-001", 42L, 3, "order-i7-001",
                emptySnapshot, producerOtelContext, null
        );

        // when
        sut.onStockCommitRequested(event);

        // then — publish 시점 OTel traceId 가 producer 측 값과 일치
        assertThat(traceIdAtPublish.get()).isEqualTo(EXPECTED_TRACE_ID);

        // then — 리스너 종료 후 OTel Context 가 root(span 없음) 로 복원
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    // -----------------------------------------------------------------------
    // TC-I7-2: onStockRestoreRequested — publish 시점에 producer 측 OTel Context 활성화 검증
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockRestoreRequested — publish 시점에 producer 측 OTel traceId가 활성화된다")
    void onStockRestoreRequested_shouldActivateOtelContextDuringPublish() {
        // given: producer 측 OTel Span 포함 Context 캡처
        SpanContext spanContext = SpanContext.create(
                EXPECTED_TRACE_ID, EXPECTED_SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault()
        );
        Span producerSpan = Span.wrap(spanContext);
        Context producerOtelContext = Context.current().with(producerSpan);
        ContextSnapshot emptySnapshot = ContextSnapshotFactory.builder().build().captureAll();

        AtomicReference<String> traceIdAtPublish = new AtomicReference<>();
        doAnswer(invocation -> {
            traceIdAtPublish.set(Span.current().getSpanContext().getTraceId());
            return null;
        }).when(stockRestorePublisher).publishPayload(any());

        StockRestoreRequestedEvent event = new StockRestoreRequestedEvent(
                "a1b2c3d4-e5f6-7890-abcd-ef1234567890", "order-i7-002", 99L, 5,
                emptySnapshot, producerOtelContext, null
        );

        // when
        sut.onStockRestoreRequested(event);

        // then — publish 시점 OTel traceId 가 producer 측 값과 일치
        assertThat(traceIdAtPublish.get()).isEqualTo(EXPECTED_TRACE_ID);

        // then — 리스너 종료 후 OTel Context 복원
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    // -----------------------------------------------------------------------
    // TC-I7-3: 리스너 종료 후 호출 전 OTel Context 로 복원
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("onStockCommitRequested — 리스너 종료 후 OTel Context가 호출 전 상태로 복원된다")
    void onStockCommitRequested_afterListener_shouldRestoreCallerOtelContext() {
        // given: 호출 스레드에 다른 span 활성화
        SpanContext callerSpanCtx = SpanContext.create(
                "11111111111111111111111111111111", "aaaaaaaaaaaaaaaa",
                TraceFlags.getSampled(), TraceState.getDefault()
        );
        Span callerSpan = Span.wrap(callerSpanCtx);

        // producer 측 다른 traceId
        SpanContext producerSpanCtx = SpanContext.create(
                EXPECTED_TRACE_ID, EXPECTED_SPAN_ID,
                TraceFlags.getSampled(), TraceState.getDefault()
        );
        Span producerSpan = Span.wrap(producerSpanCtx);
        Context producerOtelContext = Context.current().with(producerSpan);
        ContextSnapshot emptySnapshot = ContextSnapshotFactory.builder().build().captureAll();

        StockCommitRequestedEvent event = new StockCommitRequestedEvent(
                "evt-i7-commit-003", "order-i7-003", 10L, 1, "order-i7-003",
                emptySnapshot, producerOtelContext, null
        );

        // when: 호출 스레드에 callerSpan 활성화 상태에서 listener 호출
        try (io.opentelemetry.context.Scope ignored = Context.current().with(callerSpan).makeCurrent()) {
            sut.onStockCommitRequested(event);

            // then — 리스너 종료 후 호출 전 span(callerSpan) 으로 복원
            assertThat(Span.current().getSpanContext().getTraceId())
                    .isEqualTo("11111111111111111111111111111111");
        }
    }
}
