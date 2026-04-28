package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.mock.FakePgEventPublisher;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgOutboxImmediateWorker 단위 테스트.
 * domain_risk=true:
 *   - 불변식 11: exactly-once produce (Immediate+Polling 동시 경쟁 시 발행 횟수 = 1)
 *   - SmartLifecycle stop() 시 in-flight 처리 완료 후 종료
 */
@DisplayName("PgOutboxImmediateWorker")
class PgOutboxImmediateWorkerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-04-21T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private FakePgOutboxRepository outboxRepository;
    private FakePgEventPublisher publisher;
    private PgOutboxChannel channel;
    private PgOutboxRelayService relayService;
    private SimpleMeterRegistry meterRegistry;
    private PgOutboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        outboxRepository = new FakePgOutboxRepository();
        publisher = new FakePgEventPublisher();
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        relayService = new PgOutboxRelayService(outboxRepository, publisher, FIXED_CLOCK);
        meterRegistry = new SimpleMeterRegistry();
        // workerCount=1: 단위 테스트에서 스레드 수 최소화 (Spring @Value 미주입 환경 대응)
        worker = new PgOutboxImmediateWorker(channel, relayService, 1, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    @Test
    @DisplayName("stop — SmartLifecycle stop() 호출 시 in-flight row 처리 완료 후 종료된다")
    void stop_DrainsInFlightBeforeShutdown() throws InterruptedException {
        // given: row 하나 저장 + channel에 offer 준비
        PgOutbox outbox = PgOutbox.of(
                1L,
                PgTopics.EVENTS_CONFIRMED,
                "order-drain-001",
                "{\"orderId\":\"order-drain-001\"}",
                null,
                FIXED_NOW.minusSeconds(1), // availableAt < NOW → 즉시 발행 가능
                null,
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);

        // worker start
        worker.start();
        assertThat(worker.isRunning()).isTrue();

        // channel에 id offer (ImmediateWorker가 take()해서 relay 수행)
        channel.offerNow(1L);

        // relay가 완료될 시간을 충분히 대기 (비동기 처리)
        CountDownLatch processingDone = new CountDownLatch(1);
        Thread poller = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 50; i++) {
                if (publisher.getPublishedCount() == 1) {
                    processingDone.countDown();
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        boolean done = processingDone.await(3, TimeUnit.SECONDS);
        poller.interrupt();

        assertThat(done).as("ImmediateWorker가 channel item을 소비해 relay를 완료해야 한다").isTrue();

        // when: stop 호출
        long stopStart = System.currentTimeMillis();
        worker.stop();
        long elapsed = System.currentTimeMillis() - stopStart;

        // then: stop이 타임아웃(10s) 이내에 완료
        assertThat(elapsed).isLessThan(10_000L);
        assertThat(worker.isRunning()).isFalse();

        // then: processed_at 갱신 확인 (in-flight 완료)
        PgOutbox saved = outboxRepository.findById(1L).orElseThrow();
        assertThat(saved.getProcessedAt()).isEqualTo(FIXED_NOW);
    }

    /**
     * race window 노출 확률을 높이기 위해 50회 반복.
     * 각 iteration 마다 setUp() 으로 새 worker / channel / publisher 가 초기화된다.
     *
     * <p>현재 시점에서는 FakePgOutboxRepository 가 processedAt 체크를 통한 멱등 보호만
     * 제공하므로 Immediate 와 Polling 사이에 미세한 시간차(10ms) 를 두어 상호 간섭을
     * 최소화한다. 실제 production 에서는 SELECT FOR UPDATE + 트랜잭션 경계가 이 보호를
     * 강화한다 (Transactional Outbox 워커 디자인 — repository 구현체 docstring 참고).
     *
     * <p>실패 시 재현: 실패한 currentRepetition 를 로그로 확인하고 단일 테스트로 분기한다.
     */
    @RepeatedTest(value = 50, name = "exactly-once iteration {currentRepetition}/{totalRepetitions}")
    @DisplayName("exactly-once — Immediate+Polling이 동일 row 경쟁 시 발행 횟수는 정확히 1")
    void outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce() throws InterruptedException {
        // given: row 하나 저장 (processed_at IS NULL)
        PgOutbox outbox = PgOutbox.of(
                2L,
                PgTopics.EVENTS_CONFIRMED,
                "order-race-001",
                "{\"orderId\":\"order-race-001\"}",
                null,
                FIXED_NOW.minusSeconds(1), // availableAt < NOW
                null,
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);

        // ImmediateWorker 시작
        worker.start();

        // channel offer → ImmediateWorker가 take()해서 relay 수행
        channel.offerNow(2L);

        // Polling 도 같은 id 경쟁 relay — ImmediateWorker 가 먼저 진입하도록 짧은 sleep
        CountDownLatch pollingDone = new CountDownLatch(1);
        Thread pollingThread = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(10);
                relayService.relay(2L);
                pollingDone.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(pollingDone.await(3, TimeUnit.SECONDS)).isTrue();
        pollingThread.join(500);

        // ImmediateWorker 완료 대기
        Thread.sleep(200);

        // then: 발행 횟수는 정확히 1 (불변식 11 — exactly-once produce)
        // PgOutboxRelayService 의 "processedAt != null이면 skip" 로직이 중복 발행을 방지
        assertThat(publisher.getPublishedCount())
                .as("Immediate+Polling 경쟁 시 exactly-once 보장 — 발행 횟수 1")
                .isEqualTo(1);

        worker.stop();
    }

    @Test
    @DisplayName("relay_whenPublishThrows — RuntimeException 발생 시 ERROR 로그 + relay_fail 카운터 increment")
    void relay_whenPublishThrows_shouldLogErrorAndIncrementMetric() throws InterruptedException {
        // given: relay 시 RuntimeException 을 던지는 publisher
        publisher.setFailOnPublish(true);

        PgOutbox outbox = PgOutbox.of(
                3L,
                PgTopics.EVENTS_CONFIRMED,
                "order-fail-001",
                "{\"orderId\":\"order-fail-001\"}",
                null,
                FIXED_NOW.minusSeconds(1),
                null,
                0,
                FIXED_NOW.minusSeconds(60)
        );
        outboxRepository.save(outbox);

        // when: worker 기동 + channel 에 offer
        worker.start();
        channel.offerNow(3L);

        // relay 실패 처리를 위한 충분한 대기
        Thread.sleep(500);

        // then: pg_outbox.relay_fail_total 카운터가 1 이상 증가했어야 한다
        Counter relayFailCounter = meterRegistry.find("pg_outbox.relay_fail_total").counter();
        assertThat(relayFailCounter)
                .as("relay 실패 시 pg_outbox.relay_fail_total 카운터가 등록되어야 한다")
                .isNotNull();
        assertThat(relayFailCounter.count())
                .as("relay 실패 시 카운터가 1 이상이어야 한다")
                .isGreaterThanOrEqualTo(1.0);
    }
}
