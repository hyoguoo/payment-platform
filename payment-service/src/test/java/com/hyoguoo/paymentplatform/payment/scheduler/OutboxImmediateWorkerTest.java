package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakeMessagePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("OutboxImmediateWorker 테스트")
class OutboxImmediateWorkerTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 4, 21, 12, 0, 0);
    private static final String ORDER_ID = "order-imm-001";

    private MeterRegistry meterRegistry;
    private FakeMessagePublisher fakeMessagePublisher;
    private PaymentOutboxRepository mockOutboxRepository;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private LocalDateTimeProvider mockLocalDateTimeProvider;
    private OutboxRelayService outboxRelayService;
    private PaymentConfirmChannel channel;
    private OutboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        fakeMessagePublisher = new FakeMessagePublisher();
        mockOutboxRepository = Mockito.mock(PaymentOutboxRepository.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);

        given(mockLocalDateTimeProvider.now()).willReturn(FIXED_NOW);

        outboxRelayService = new OutboxRelayService(
                mockOutboxRepository,
                fakeMessagePublisher,
                mockPaymentLoadUseCase,
                mockLocalDateTimeProvider
        );

        channel = new PaymentConfirmChannel(1000, meterRegistry);
        worker = new OutboxImmediateWorker(channel, outboxRelayService);
        ReflectionTestUtils.setField(worker, "workerCount", 1);
        ReflectionTestUtils.setField(worker, "virtualThreads", true);
    }

    @AfterEach
    void tearDown() {
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    // ─── ADR-25: stop — SmartLifecycle graceful drain 검증 ────────────────

    /**
     * ADR-25: SIGTERM 수신 시 stop(Runnable) 경로가 호출된다.
     * in-flight relay() 가 완료된 이후에야 콜백이 실행되어야 한다.
     * <p>
     * 검증 항목:
     * 1. relay() 완료 시각 < stop() 콜백 실행 시각  (drain 순서 보장)
     * 2. 콜백 실행 후 isRunning() == false         (생명주기 상태 전환)
     */
    @Test
    @DisplayName("stop_DrainsInFlightBeforeShutdown — in-flight relay 완료 후 콜백 실행")
    void stop_DrainsInFlightBeforeShutdown() throws InterruptedException {
        // given: relay()가 실행 중임을 확인하기 위한 래치와 타임스탬프 추적
        CountDownLatch relayStarted = new CountDownLatch(1);
        AtomicLong relayEndNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong callbackNanos = new AtomicLong(0);

        OutboxRelayService slowRelay = Mockito.mock(OutboxRelayService.class);
        Mockito.doAnswer(invocation -> {
            relayStarted.countDown();          // relay 시작 신호
            // interrupt-safe 슬립: 인터럽트가 들어와도 남은 시간을 채운 뒤 완료
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (System.nanoTime() < deadline) {
                // busy-wait — relay() 가 외부 인터럽트에 의해 중단되지 않음을 시뮬레이션
            }
            relayEndNanos.set(System.nanoTime());
            return null;
        }).when(slowRelay).relay(anyString());

        OutboxImmediateWorker drainWorker = new OutboxImmediateWorker(channel, slowRelay);
        ReflectionTestUtils.setField(drainWorker, "workerCount", 1);
        ReflectionTestUtils.setField(drainWorker, "virtualThreads", true);

        drainWorker.start();
        channel.offer(ORDER_ID);           // 워커가 relay() 를 시작하도록 큐에 삽입

        // relay() 가 실제로 시작될 때까지 대기
        assertThat(relayStarted.await(3, TimeUnit.SECONDS))
                .as("relay() 가 3초 내 시작되어야 한다").isTrue();

        // when: relay() 실행 중 stop() 호출
        drainWorker.stop(() -> callbackNanos.set(System.nanoTime()));

        // then 1: drain 순서 — relay() 완료 후 콜백 실행
        assertThat(relayEndNanos.get())
                .as("relay() 가 콜백보다 먼저 완료되어야 한다 (graceful drain)")
                .isLessThanOrEqualTo(callbackNanos.get());

        // then 2: 생명주기 상태 전환
        assertThat(drainWorker.isRunning()).isFalse();
    }

    // ─── ADR-26: start — Virtual Thread 워커 수 검증 ─────────────────────

    /**
     * ADR-26: start() 시 workerCount 설정값만큼의 VT 워커가 생성된다.
     * workers 리스트 크기로 스폰 수를 확인하고, 각 스레드가 virtual thread 임을 검증한다.
     */
    @Test
    @DisplayName("start_SpawnsConfiguredNumberOfWorkers — workerCount 설정값만큼 VT 워커 스폰")
    @SuppressWarnings("unchecked")
    void start_SpawnsConfiguredNumberOfWorkers() {
        // given: workerCount=3, virtualThreads=true
        OutboxImmediateWorker vtWorker = new OutboxImmediateWorker(channel, outboxRelayService);
        ReflectionTestUtils.setField(vtWorker, "workerCount", 3);
        ReflectionTestUtils.setField(vtWorker, "virtualThreads", true);

        try {
            // when
            vtWorker.start();

            // then: workers 리스트에 정확히 3개 스레드가 생성됨
            List<Thread> workers = (List<Thread>) ReflectionTestUtils.getField(vtWorker, "workers");
            assertThat(workers)
                    .as("start() 후 workerCount(3)만큼 워커가 생성되어야 한다")
                    .hasSize(3);

            // then: 모든 워커가 Virtual Thread 임을 확인
            await().atMost(1, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            workers.forEach(t ->
                                    assertThat(t.isVirtual())
                                            .as("ADR-26: 워커는 Virtual Thread 여야 한다")
                                            .isTrue()
                            )
                    );
        } finally {
            vtWorker.stop();
        }
    }

    // ─── Race: Immediate + Polling 경쟁 시 publish 1회만 ─────────────────────

    @Test
    @DisplayName("outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce — 동일 orderId 두 스레드 relay() → publish 1회")
    void outbox_publish_WhenImmediateAndPollingRace_ShouldEmitOnce() throws InterruptedException {
        // given: claimToInFlight — 첫 번째 호출만 성공, 두 번째(racing)는 false
        PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .allArgsBuild();

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .orderId(ORDER_ID)
                .paymentKey("pk-race-001")
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .paymentOrderList(List.of())
                .allArgsBuild();

        // 첫 번째 claim 성공, 두 번째(경쟁 워커) 실패
        given(mockOutboxRepository.claimToInFlight(anyString(), any(LocalDateTime.class)))
                .willReturn(true)
                .willReturn(false);
        given(mockOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(outbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(paymentEvent);
        given(mockOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox);

        // when: Immediate 워커(스레드 1)와 Polling 워커(스레드 2)가 동시에 동일 orderId를 relay
        Thread immediateThread = Thread.ofVirtual().name("immediate-race").unstarted(
                () -> outboxRelayService.relay(ORDER_ID)
        );
        Thread pollingThread = Thread.ofVirtual().name("polling-race").unstarted(
                () -> outboxRelayService.relay(ORDER_ID)
        );

        immediateThread.start();
        pollingThread.start();
        immediateThread.join(2000);
        pollingThread.join(2000);

        // then: publish는 정확히 1회
        assertThat(fakeMessagePublisher.count()).isEqualTo(1);
    }

    // ─── stop: isRunning false 후 재기동 방지 확인 ────────────────────────

    @Test
    @DisplayName("stop_WhenAlreadyStopped_IsRunningReturnsFalse — stop() 후 isRunning()이 false를 반환한다")
    void stop_WhenAlreadyStopped_IsRunningReturnsFalse() {
        // given
        worker.start();
        assertThat(worker.isRunning()).isTrue();

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        // when
        worker.stop(() -> callbackInvoked.set(true));

        // then
        await().atMost(5, TimeUnit.SECONDS).untilTrue(callbackInvoked);
        assertThat(worker.isRunning()).isFalse();
    }
}
