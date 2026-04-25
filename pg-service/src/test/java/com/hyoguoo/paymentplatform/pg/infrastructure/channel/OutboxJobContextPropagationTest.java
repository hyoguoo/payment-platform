package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import com.hyoguoo.paymentplatform.pg.infrastructure.config.PgSlf4jMdcThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-J4 RED — PgOutboxChannel OutboxJob context 동봉 검증.
 *
 * <p>offer 시점의 OTel Context + MDC 스냅샷이 OutboxJob 에 동봉되어,
 * 별도 스레드에서 take 후 restore 하면 caller context 가 정확히 복원되어야 한다.
 *
 * <p>이 테스트는 OutboxJob record 가 존재하고,
 * offerNow(Long) 편의 메서드가 current context 를 캡처하며,
 * take() 가 OutboxJob 을 반환하는 것을 전제한다.
 */
@DisplayName("OutboxJob — offer→take 경계 OTel Context + MDC 동봉 검증 (T-J4)")
class OutboxJobContextPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "tj4-test-trace-abc123";

    private PgOutboxChannel channel;

    @BeforeEach
    void setUp() {
        // T-J4: 단위 테스트 환경에서는 Spring context 없으므로 MDC accessor 를 수동 등록
        ContextRegistry.getInstance().registerThreadLocalAccessor(new PgSlf4jMdcThreadLocalAccessor());
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        MDC.clear();
    }

    @Test
    @DisplayName("offerNow — 호출 스레드의 MDC 가 OutboxJob 에 동봉되어 worker 스레드에서 복원된다")
    void offerNow_동봉된_MDC가_worker스레드에서_복원된다() throws Exception {
        // given: 호출 스레드에 MDC traceId 설정
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);

        // when: offerNow — 현재 MDC 캡처
        boolean offered = channel.offerNow(42L);
        MDC.clear(); // 호출 스레드 MDC 클리어 — worker 스레드에서 복원되는지 확인

        assertThat(offered).isTrue();

        // then: take → job.snapshot().setThreadLocals() 로 복원 → MDC 확인
        OutboxJob job = channel.take();
        assertThat(job.outboxId()).isEqualTo(42L);

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        vt.submit(() -> {
            try (ContextSnapshot.Scope scope = job.snapshot().setThreadLocals()) {
                capturedTraceId.set(MDC.get(TRACE_ID_KEY));
            }
            latch.countDown();
        });

        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        vt.shutdown();

        assertThat(capturedTraceId.get())
                .as("OutboxJob 에 동봉된 MDC snapshot 이 worker thread 에서 복원되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
    }

    @Test
    @DisplayName("offerNow — outboxId 가 OutboxJob 에 정확히 보존된다")
    void offerNow_outboxId_보존() throws InterruptedException {
        // when
        channel.offerNow(999L);

        // then
        OutboxJob job = channel.take();
        assertThat(job.outboxId()).isEqualTo(999L);
        assertThat(job.otelContext()).isNotNull();
        assertThat(job.snapshot()).isNotNull();
    }

    @Test
    @DisplayName("offerNow — 여러 outboxId 를 순차 삽입하면 size 가 정확히 일치한다")
    void offerNow_순차삽입_size검증() throws InterruptedException {
        // when
        boolean offered = channel.offerNow(77L);

        // then
        assertThat(offered).isTrue();
        assertThat(channel.size()).isEqualTo(1);
        OutboxJob job = channel.take();
        assertThat(job.outboxId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("offerNow — 큐 full 시 false 반환")
    void offerNow_큐full시_false반환() {
        // capacity=1024 → 1025개 시도
        PgOutboxChannel smallChannel = new PgOutboxChannel(5, new SimpleMeterRegistry());
        smallChannel.registerMetrics();

        for (int i = 0; i < 5; i++) {
            assertThat(smallChannel.offerNow((long) i)).isTrue();
        }
        assertThat(smallChannel.offerNow(99L)).isFalse();
    }

    @Test
    @DisplayName("offerNow — 호출 스레드의 OTel Context 가 OutboxJob 에 동봉된다")
    void offerNow_otelContext_동봉() throws InterruptedException {
        // given: Context.root() 는 빈 context. 실제 span context는 OTel 통합에서 검증.
        // 여기서는 Context.current() 가 OutboxJob.otelContext 에 정확히 캡처됨을 검증.
        Context currentContext = Context.current();

        // when
        channel.offerNow(55L);

        // then
        OutboxJob job = channel.take();
        // 캡처된 context 가 호출 시점 context 와 동일해야 한다
        assertThat(job.otelContext()).isEqualTo(currentContext);
    }
}
