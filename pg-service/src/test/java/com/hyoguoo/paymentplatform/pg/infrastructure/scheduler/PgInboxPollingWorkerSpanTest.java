package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.mock.FakeSpanExporter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PgInboxPollingWorker — 좀비 회수 처리마다 자체 추적 구간을 여는지 검증.
 *
 * <p>공식 OTel 테스트 아티팩트(opentelemetry-sdk-testing) 없이, SDK export 계약을 직접 구현한
 * {@link FakeSpanExporter} 로 실제 기록된 span 을 읽어 단정한다 — 속성만 붙고 span 자체가
 * 열리지 않는 실패를 잡아내려면 mock 상호작용이 아니라 실제 export 결과를 봐야 한다.
 */
@DisplayName("PgInboxPollingWorker — 좀비 회수 추적 구간")
class PgInboxPollingWorkerSpanTest {

    private static final String STORED_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private PgInboxRepository inboxRepository;
    private PgInboxProcessUseCase processor;
    private FakeSpanExporter spanExporter;
    private SdkTracerProvider tracerProvider;
    private PgInboxPollingWorker pollingWorker;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(PgInboxRepository.class);
        processor = mock(PgInboxProcessUseCase.class);
        spanExporter = new FakeSpanExporter();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        Tracer tracer = tracerProvider.get("pg-inbox-polling-worker-test");

        pollingWorker = new PgInboxPollingWorker(
                inboxRepository, processor, 10, 60_000L, 60_000L, new SimpleMeterRegistry(), tracer);

        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    @DisplayName("좀비_회수_처리마다_자체_구간이_생성된다")
    void 좀비_회수_처리마다_자체_구간이_생성된다() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L));
        when(inboxRepository.findStoredTraceparent(1L))
                .thenReturn(Optional.of(STORED_TRACEPARENT));
        when(inboxRepository.findById(1L))
                .thenReturn(Optional.of(pgInboxFixture("ORDER-1")));

        // when
        pollingWorker.poll();

        // then: 속성만 붙고 span 자체가 안 열리는 실패를 잡기 위해 실제 export 건수를 본다
        List<SpanData> exportedSpans = spanExporter.getExportedSpans();
        assertThat(exportedSpans)
                .as("좀비 회수 1건 처리 → 자체 구간 1개가 실제로 export 되어야 한다")
                .hasSize(1);
    }

    @Test
    @DisplayName("생성된_구간에_주문_번호_속성이_붙는다")
    void 생성된_구간에_주문_번호_속성이_붙는다() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(2L));
        when(inboxRepository.findStoredTraceparent(2L))
                .thenReturn(Optional.of(STORED_TRACEPARENT));
        when(inboxRepository.findById(2L))
                .thenReturn(Optional.of(pgInboxFixture("ORDER-2")));

        // when
        pollingWorker.poll();

        // then
        SpanData span = spanExporter.getExportedSpans().get(0);
        assertThat(span.getAttributes().asMap())
                .as("주문 번호가 속성으로 붙어야 한다")
                .anySatisfy((key, value) -> {
                    assertThat(key.getKey()).isEqualTo("order_id");
                    assertThat(value).isEqualTo("ORDER-2");
                });
    }

    @Test
    @DisplayName("복원된_문맥이_생성_구간의_부모가_된다")
    void 복원된_문맥이_생성_구간의_부모가_된다() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(3L));
        when(inboxRepository.findStoredTraceparent(3L))
                .thenReturn(Optional.of(STORED_TRACEPARENT));
        when(inboxRepository.findById(3L))
                .thenReturn(Optional.of(pgInboxFixture("ORDER-3")));

        // when
        pollingWorker.poll();

        // then: 복원된 traceparent 의 trace-id / parent-span-id 가 생성된 구간의 부모로 이어져야 한다
        SpanData span = spanExporter.getExportedSpans().get(0);
        assertThat(span.getParentSpanContext().getTraceId())
                .as("생성 구간은 복원된 문맥과 같은 trace 에 속해야 한다")
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.getParentSpanContext().getSpanId())
                .as("생성 구간의 부모 span id 는 복원된 traceparent 의 span id 여야 한다")
                .isEqualTo("00f067aa0ba902b7");
    }

    private static PgInbox pgInboxFixture(String orderId) {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        return PgInbox.create(orderId, 1000L, now);
    }

    // Mockito argument helpers
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
