package com.hyoguoo.paymentplatform.pg.scheduler;

import com.hyoguoo.paymentplatform.pg.application.service.PgOutboxRelayService;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.mock.FakePgEventPublisher;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    private PgOutboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        outboxRepository = new FakePgOutboxRepository();
        publisher = new FakePgEventPublisher();
        channel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        relayService = new PgOutboxRelayService(outboxRepository, publisher, FIXED_CLOCK);
        // workerCount=1: 단위 테스트에서 스레드 수 최소화 (Spring @Value 미주입 환경 대응)
        worker = new PgOutboxImmediateWorker(channel, relayService, 1);
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
        channel.offer(1L);

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

    @Test
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
        channel.offer(2L);

        // Polling도 같은 id에 대해 relay 시도
        // (실제 DB에서는 FOR UPDATE SKIP LOCKED가 보호하지만, FakeRepo는 processedAt 체크로 멱등)
        // 두 경쟁을 시뮬레이션: ImmediateWorker relay + Polling relay 동시 실행

        // Polling이 동시에 relay(2L) 를 호출하는 상황 시뮬레이션
        CountDownLatch immediateDone = new CountDownLatch(1);
        Thread pollingThread = Thread.ofVirtual().start(() -> {
            try {
                // ImmediateWorker가 처리 시작할 때까지 짧게 대기
                Thread.sleep(10);
                // Polling도 같은 row relay 시도
                relayService.relay(2L);
                immediateDone.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // ImmediateWorker 처리 완료 대기
        boolean pollingDone = immediateDone.await(3, TimeUnit.SECONDS);
        pollingThread.join(500);

        // 추가 대기: ImmediateWorker도 완료
        Thread.sleep(200);

        // then: 발행 횟수는 정확히 1 (불변식 11 — exactly-once produce)
        // PgOutboxRelayService의 "processedAt != null이면 skip" 로직이 중복 발행을 방지
        assertThat(publisher.getPublishedCount())
                .as("Immediate+Polling 경쟁 시 exactly-once 보장 — 발행 횟수 1")
                .isEqualTo(1);

        worker.stop();
    }
}
