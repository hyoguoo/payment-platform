package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import com.hyoguoo.paymentplatform.pg.infrastructure.config.PgSlf4jMdcThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * PgOutboxImmediateWorker 의 MDC 전파 검증.
 *
 * <p>offer 한 스레드의 MDC traceId 가 워커 스레드의 relay() 안까지 이어져야 하고, 처리가 끝나면 워커 스레드에
 * 남지 않아야 한다. 채널이 offer 시점에 ContextSnapshot 을 캡처하고 워커가 처리 직전에 복원한다.
 */
@DisplayName("PgOutboxImmediateWorker — MDC 전파")
class PgOutboxImmediateWorkerMdcPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "test-trace-abc123";
    private static final String ABSENT = "<absent>";

    private PgOutboxChannel channel;
    private CapturingRelayService capturingRelayService;
    private PgOutboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        // 단위 테스트 환경엔 Spring context 가 없으므로 MDC accessor 를 수동 등록한다.
        ContextRegistry.getInstance().registerThreadLocalAccessor(new PgSlf4jMdcThreadLocalAccessor());
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        capturingRelayService = new CapturingRelayService();
        worker = new PgOutboxImmediateWorker(channel, capturingRelayService, 1, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    @Test
    @DisplayName("offer 한 스레드의 MDC traceId 가 워커의 relay 안에서 보이고, 다음 작업에는 남지 않는다")
    void offer_스레드의_MDC가_relay까지_전파되고_다음_작업에_남지_않는다() throws InterruptedException {
        // given
        worker.start();

        // when: traceId 를 둔 채 offer, 이어서 traceId 없이 offer
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);
        channel.offerNow(1L);
        MDC.clear();
        channel.offerNow(2L);

        // then
        assertThat(capturingRelayService.done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(capturingRelayService.captured.get(1L))
                .as("offer 시점 MDC 가 워커 스레드 relay 안에서 복원되어야 한다")
                .isEqualTo(TRACE_ID_VALUE);
        assertThat(capturingRelayService.captured.get(2L))
                .as("앞 작업의 MDC 가 워커 스레드에 남아 다음 작업으로 새면 안 된다")
                .isEqualTo(ABSENT);
    }

    /**
     * relay() 진입 시점의 MDC traceId 를 작업별로 기록하는 테스트 전용 relay service.
     */
    static class CapturingRelayService extends PgOutboxRelayService {

        private final Map<Long, String> captured = new ConcurrentHashMap<>();
        private final CountDownLatch done = new CountDownLatch(2);

        CapturingRelayService() {
            super(null, null, null);
        }

        @Override
        public void relay(long id) {
            String traceId = MDC.get(TRACE_ID_KEY);
            captured.put(id, traceId == null ? ABSENT : traceId);
            done.countDown();
        }
    }
}
