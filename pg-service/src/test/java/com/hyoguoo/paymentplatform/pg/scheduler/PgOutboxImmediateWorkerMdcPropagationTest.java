package com.hyoguoo.paymentplatform.pg.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * T-E1 RED — PgOutboxImmediateWorker relayExecutor MDC 전파 확인.
 *
 * <p>MDC에 traceId=X 설정 후 channel.offer() → relay 람다 내부에서 동일 값이 읽혀야 한다.
 * ContextExecutorService.wrap 적용 전에는 VT 경계에서 MDC가 비어 FAIL 한다.
 */
@DisplayName("PgOutboxImmediateWorker — MDC 전파 (T-E1 RED)")
class PgOutboxImmediateWorkerMdcPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "test-trace-abc123";

    private PgOutboxChannel channel;
    private PgOutboxImmediateWorker worker;
    private CapturingRelayService capturingRelayService;

    @BeforeEach
    void setUp() {
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        capturingRelayService = new CapturingRelayService();
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
    @DisplayName("relayExecutor submit — 호출 스레드 MDC traceId 가 VT 내부에서 승계된다")
    void relayExecutor_submit_propagatesMdcToVirtualThread() throws InterruptedException {
        // given: 호출 스레드에 traceId 설정
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);

        worker.start();

        // when: channel 에 offer — workerLoop 가 take() → relayExecutor.submit(() -> relay(id)) 경로 진입
        channel.offer(999L); // id 는 실제 row 없어도 relay() 내부에서 예외 무시됨

        // then: VT 내부에서 MDC 캡처 대기
        boolean captured = capturingRelayService.latch.await(3, TimeUnit.SECONDS);

        assertThat(captured).as("3초 이내에 VT 내부 MDC 캡처가 완료되어야 한다").isTrue();
        assertThat(capturingRelayService.capturedTraceId.get())
                .as("VT 내부에서 호출 스레드의 traceId 가 승계되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
    }

    /**
     * relay() 진입 시점의 MDC 값을 캡처하는 테스트 전용 relay service.
     * ContextExecutorService.wrap 적용 전에는 MDC 가 null 이므로 테스트가 FAIL 한다.
     */
    static class CapturingRelayService extends PgOutboxRelayService {

        final AtomicReference<String> capturedTraceId = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        CapturingRelayService() {
            super(null, null, null);
        }

        @Override
        public void relay(long id) {
            capturedTraceId.set(MDC.get("traceId"));
            latch.countDown();
            // delegate 호출 생략 — MDC 캡처가 목적
        }
    }
}
