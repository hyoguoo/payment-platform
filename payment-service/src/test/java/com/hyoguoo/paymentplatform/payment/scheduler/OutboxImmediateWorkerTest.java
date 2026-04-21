package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

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

    // ─── stop: SmartLifecycle 종료 드레인 검증 ─────────────────────────────

    @Test
    @DisplayName("stop_DrainsInFlightBeforeShutdown — stop() 후 콜백이 호출되고 워커가 종료된다")
    void stop_DrainsInFlightBeforeShutdown() throws InterruptedException {
        // given
        worker.start();
        assertThat(worker.isRunning()).isTrue();

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        // when
        worker.stop(() -> callbackInvoked.set(true));

        // then: 5 s 이내 콜백 호출, 워커 isRunning false
        await().atMost(5, TimeUnit.SECONDS).untilTrue(callbackInvoked);
        assertThat(worker.isRunning()).isFalse();
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
}
