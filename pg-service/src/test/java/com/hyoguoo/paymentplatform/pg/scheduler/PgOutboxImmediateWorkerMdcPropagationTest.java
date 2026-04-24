package com.hyoguoo.paymentplatform.pg.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import com.hyoguoo.paymentplatform.pg.infrastructure.config.PgSlf4jMdcThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T-E1 RED → GREEN — PgOutboxImmediateWorker relayExecutor MDC 전파 확인.
 *
 * <p>relayExecutor(ContextExecutorService.wrap 적용)에 직접 submit 시
 * 호출 스레드 MDC traceId 가 VT 내부에서 승계되어야 한다.
 *
 * <p>ContextExecutorService.wrap 적용 전에는 VT 경계에서 MDC 가 비어 FAIL 한다.
 * apply 후에는 ContextRegistry 에 등록된 Slf4jMdcThreadLocalAccessor 가 MDC 를 캡처·복원한다.
 */
@DisplayName("PgOutboxImmediateWorker — relayExecutor MDC 전파 (T-E1)")
class PgOutboxImmediateWorkerMdcPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "test-trace-abc123";

    private PgOutboxChannel channel;
    private PgOutboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        // T-E1: 단위 테스트 환경에서는 Spring context 가 없으므로 MDC accessor 를 수동 등록
        ContextRegistry.getInstance().registerThreadLocalAccessor(new PgSlf4jMdcThreadLocalAccessor());
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        CapturingRelayService capturingRelayService = new CapturingRelayService();
        worker = new PgOutboxImmediateWorker(channel, capturingRelayService, 1);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    @Test
    @DisplayName("relayExecutor — 호출 스레드 MDC traceId 가 VT 내부에서 승계된다")
    void relayExecutor_propagatesMdcToVirtualThread() throws Exception {
        // given: worker 기동 → relayExecutor(ContextExecutorService.wrap) 초기화
        worker.start();

        // relayExecutor 필드를 직접 꺼내어 검증
        // (workerLoop VT 가 아닌 테스트 스레드에서 submit — 호출 스레드의 MDC 를 캡처하는 동작 검증)
        ExecutorService relayExecutor = (ExecutorService) ReflectionTestUtils.getField(worker, "relayExecutor");

        // 호출 스레드에 traceId 설정
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // when: 테스트 스레드(MDC 설정됨)에서 relayExecutor 에 직접 submit
        Future<?> f = relayExecutor.submit(() -> {
            capturedTraceId.set(MDC.get(TRACE_ID_KEY));
            latch.countDown();
        });
        f.get(3, TimeUnit.SECONDS);

        // then: VT 내부에서 테스트 스레드의 traceId 승계
        assertThat(latch.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(capturedTraceId.get())
                .as("relayExecutor(VT) 내부에서 호출 스레드의 traceId 가 승계되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
    }

    /**
     * relay() 진입 시점의 MDC 값을 캡처하는 테스트 전용 relay service.
     */
    static class CapturingRelayService extends PgOutboxRelayService {

        CapturingRelayService() {
            super(null, null, null);
        }

        @Override
        public void relay(long id) {
            // workerLoop 경유 relay — 이 경로는 이 테스트에서 직접 검증하지 않음
        }
    }
}
