package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PgInboxImmediateWorker 단위 테스트.
 *
 * <p>domain_risk=false — SmartLifecycle 라이프사이클 + VT 워커 동작 검증.
 * PgOutboxImmediateWorkerTest 거울 패턴.
 */
@DisplayName("PgInboxImmediateWorker")
class PgInboxImmediateWorkerTest {

    private static final Instant NOW = Instant.parse("2026-05-09T00:00:00Z");

    private PgInboxChannel channel;
    private PgInboxProcessUseCase processor;
    private PgInboxRepository inboxRepository;
    private SimpleMeterRegistry meterRegistry;
    private PgInboxImmediateWorker worker;

    @BeforeEach
    void setUp() {
        channel = new PgInboxChannel(1024, new SimpleMeterRegistry());
        channel.registerMetrics();
        processor = mock(PgInboxProcessUseCase.class);
        inboxRepository = mock(PgInboxRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        // workerCount=1: 단위 테스트에서 스레드 수 최소화
        worker = new PgInboxImmediateWorker(channel, processor, inboxRepository, 1, meterRegistry);
    }

    @AfterEach
    void tearDown() {
        if (worker.isRunning()) {
            worker.stop();
        }
    }

    @Test
    @DisplayName("getPhase — Integer.MAX_VALUE - 100 반환 (채널보다 나중에 stop)")
    void getPhase_returnsExpectedValue() {
        assertThat(worker.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
    }

    @Test
    @DisplayName("start_createsWorkerThreads_andRunning — start() 후 isRunning() == true")
    void start_createsWorkerThreads_andRunning() {
        // when
        worker.start();

        // then
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    @DisplayName("stop_setsRunningFalse_andInterruptsWorkers — stop() 후 isRunning() == false")
    void stop_setsRunningFalse_andInterruptsWorkers() {
        // given
        worker.start();
        assertThat(worker.isRunning()).isTrue();

        // when
        worker.stop();

        // then
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    @DisplayName("workerLoop_pendingJob_callsProcessPending — 채널 offer 후 processPending 1회 호출")
    void workerLoop_pendingJob_callsProcessPending() throws InterruptedException {
        // given
        PgInbox pendingInbox = PgInbox.of("order-1", PgInboxStatus.PENDING, 10000L, null, null, NOW, NOW);
        when(inboxRepository.findById(42L)).thenReturn(Optional.of(pendingInbox));
        doNothing().when(processor).processPending(42L);

        worker.start();

        // when: 채널에 inboxId offer
        channel.offerNow(42L);

        // then: processPending 호출 (최대 3s 대기)
        verify(processor, timeout(3_000).times(1)).processPending(42L);
    }

    @Test
    @DisplayName("workerLoop_inProgressJob_callsProcessInProgressZombie — IN_PROGRESS inbox → processInProgressZombie 1회 호출")
    void workerLoop_inProgressJob_callsProcessInProgressZombie() throws InterruptedException {
        // given
        PgInbox inProgressInbox = PgInbox.of("order-2", PgInboxStatus.IN_PROGRESS, 10000L, null, null, NOW, NOW);
        when(inboxRepository.findById(99L)).thenReturn(Optional.of(inProgressInbox));
        doNothing().when(processor).processInProgressZombie(99L);

        worker.start();

        // when
        channel.offerNow(99L);

        // then: processInProgressZombie 호출 (최대 3s 대기)
        verify(processor, timeout(3_000).times(1)).processInProgressZombie(99L);
        verify(processor, never()).processPending(99L);
    }

    @Test
    @DisplayName("workerLoop_terminalJob_skipsAndLogsInfo — terminal inbox (APPROVED) → processPending / processInProgressZombie 미호출")
    void workerLoop_terminalJob_skipsAndLogsInfo() throws InterruptedException {
        // given
        PgInbox approvedInbox = PgInbox.of("order-3", PgInboxStatus.APPROVED, 10000L, "{}", null, NOW, NOW);
        CountDownLatch findLatch = new CountDownLatch(1);
        when(inboxRepository.findById(77L)).thenAnswer(inv -> {
            findLatch.countDown();
            return Optional.of(approvedInbox);
        });

        worker.start();

        // when
        channel.offerNow(77L);

        // then: findById 호출 확인 후 processPending/processInProgressZombie 미호출
        boolean found = findLatch.await(3, TimeUnit.SECONDS);
        assertThat(found).as("findById 가 호출되어야 한다").isTrue();
        // 약간 대기 후 검증 (VT 스케줄링 지연 허용)
        Thread.sleep(200);
        verify(processor, never()).processPending(77L);
        verify(processor, never()).processInProgressZombie(77L);
    }

    @Test
    @DisplayName("workerLoop_missingInbox_skipsAndLogsWarn — row 없음 → processPending / processInProgressZombie 미호출")
    void workerLoop_missingInbox_skipsAndLogsWarn() throws InterruptedException {
        // given
        CountDownLatch findLatch = new CountDownLatch(1);
        when(inboxRepository.findById(55L)).thenAnswer(inv -> {
            findLatch.countDown();
            return Optional.empty();
        });

        worker.start();

        // when
        channel.offerNow(55L);

        // then
        boolean found = findLatch.await(3, TimeUnit.SECONDS);
        assertThat(found).as("findById 가 호출되어야 한다").isTrue();
        Thread.sleep(200);
        verify(processor, never()).processPending(55L);
        verify(processor, never()).processInProgressZombie(55L);
    }

    @Test
    @DisplayName("workerLoop_runtimeException_incrementsFailCounter — RuntimeException → pg_inbox.process_fail_total +1, 워커 계속 실행")
    void workerLoop_runtimeException_incrementsFailCounter() throws InterruptedException {
        // given: PENDING inbox — processPending이 첫 번째는 RuntimeException, 두 번째는 정상
        PgInbox pendingInbox = PgInbox.of("order-fail", PgInboxStatus.PENDING, 10000L, null, null, NOW, NOW);
        when(inboxRepository.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.of(pendingInbox));

        CountDownLatch firstCallLatch = new CountDownLatch(1);
        CountDownLatch secondCallLatch = new CountDownLatch(1);

        doAnswer(inv -> {
            firstCallLatch.countDown();
            throw new RuntimeException("처리 실패");
        }).doAnswer(inv -> {
            secondCallLatch.countDown();
            return null;
        }).when(processor).processPending(org.mockito.ArgumentMatchers.anyLong());

        worker.start();

        // when: 첫 번째 offer — RuntimeException 발생
        channel.offerNow(1L);
        boolean firstProcessed = firstCallLatch.await(3, TimeUnit.SECONDS);
        assertThat(firstProcessed).as("첫 번째 processPending 호출이 이뤄져야 한다").isTrue();

        // 두 번째 offer — 정상 처리 (워커가 계속 실행 중임을 검증)
        channel.offerNow(2L);
        boolean secondProcessed = secondCallLatch.await(3, TimeUnit.SECONDS);
        assertThat(secondProcessed).as("RuntimeException 후 워커가 계속 실행되어야 한다").isTrue();

        // then: pg_inbox.process_fail_total 카운터가 1 이상
        Counter failCounter = meterRegistry.find(PgInboxImmediateWorker.PROCESS_FAIL_COUNTER_NAME).counter();
        assertThat(failCounter)
                .as("처리 실패 시 process_fail_total 카운터가 등록되어야 한다")
                .isNotNull();
        assertThat(failCounter.count())
                .as("첫 번째 처리 실패로 카운터가 1 이상이어야 한다")
                .isGreaterThanOrEqualTo(1.0);
    }
}
