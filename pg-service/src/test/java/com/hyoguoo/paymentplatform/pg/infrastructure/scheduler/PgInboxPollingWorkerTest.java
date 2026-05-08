package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PgInboxPollingWorker — PENDING / IN_PROGRESS 두 경로 좀비 회수 + 카운터 검증.
 *
 * <p>domain_risk=true: SKIP LOCKED 멱등성은 PCS-4 (PgInboxRepository) 계층 책임.
 * 본 테스트는 워커가 올바른 usecase 메서드를 호출하는지만 검증한다.
 *
 * <p>traceparent 분리(새 root span) 의도 — PHASE2 연계 예정 (Round 1 D-F5 흡수).
 * 현재는 OTel context 없이 직접 호출한다.
 */
@DisplayName("PgInboxPollingWorker")
class PgInboxPollingWorkerTest {

    private PgInboxRepository inboxRepository;
    private PgInboxProcessUseCase processor;
    private SimpleMeterRegistry meterRegistry;
    private PgInboxPollingWorker pollingWorker;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(PgInboxRepository.class);
        processor = mock(PgInboxProcessUseCase.class);
        meterRegistry = new SimpleMeterRegistry();
        pollingWorker = new PgInboxPollingWorker(inboxRepository, processor, meterRegistry);
    }

    @Test
    @DisplayName("poll_pendingZombies_callsProcessPendingForEach — findPendingZombieIds 3개 → processPending 3회 호출")
    void poll_pendingZombies_callsProcessPendingForEach() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());

        // when
        pollingWorker.poll();

        // then
        verify(processor, times(1)).processPending(1L);
        verify(processor, times(1)).processPending(2L);
        verify(processor, times(1)).processPending(3L);
        verify(processor, never()).processInProgressZombie(anyLong());
    }

    @Test
    @DisplayName("poll_inProgressZombies_callsProcessInProgressZombieForEach — findInProgressZombieIds 2개 → processInProgressZombie 2회 호출")
    void poll_inProgressZombies_callsProcessInProgressZombieForEach() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(10L, 20L));

        // when
        pollingWorker.poll();

        // then
        verify(processor, never()).processPending(anyLong());
        verify(processor, times(1)).processInProgressZombie(10L);
        verify(processor, times(1)).processInProgressZombie(20L);
    }

    @Test
    @DisplayName("poll_noZombies_noProcessCalls — 둘 다 빈 결과 → processPending / processInProgressZombie 미호출")
    void poll_noZombies_noProcessCalls() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());

        // when
        pollingWorker.poll();

        // then
        verify(processor, never()).processPending(anyLong());
        verify(processor, never()).processInProgressZombie(anyLong());
    }

    @Test
    @DisplayName("poll_processingException_incrementsZombieCounter_continues — processPending RuntimeException → 카운터 +1, 나머지 id 계속 처리")
    void poll_processingException_incrementsZombieCounter_continues() {
        // given: PENDING 3건 — 첫 번째만 RuntimeException
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());

        doThrow(new RuntimeException("처리 실패")).when(processor).processPending(1L);

        // when
        pollingWorker.poll();

        // then: 3건 모두 호출 시도 (루프 계속)
        verify(processor, times(1)).processPending(1L);
        verify(processor, times(1)).processPending(2L);
        verify(processor, times(1)).processPending(3L);

        // then: zombie_recovered_total 카운터 등록 확인 (실패 시에도 카운터 존재)
        Counter zombieCounter = meterRegistry
                .find(PgInboxPollingWorker.ZOMBIE_FAIL_COUNTER_NAME)
                .counter();
        assertThat(zombieCounter)
                .as("좀비 처리 실패 카운터가 등록되어야 한다")
                .isNotNull();
        assertThat(zombieCounter.count())
                .as("첫 번째 processPending 실패로 카운터가 1이어야 한다")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("poll_pendingTimeoutCutoff_60s — yml default pendingTimeoutMs=60000ms 정합 검증")
    void poll_pendingTimeoutCutoff_60s() {
        // given: 빈 결과 (cutoff 값 검증용)
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());

        // when
        pollingWorker.poll();

        // then: findPendingZombieIds 호출 시 thresholdMs 가 60000 이어야 한다
        org.mockito.ArgumentCaptor<Long> captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(inboxRepository).findPendingZombieIds(anyInt(), captor.capture());
        assertThat(captor.getValue())
                .as("PENDING 좀비 임계치 기본값이 60000ms 이어야 한다")
                .isEqualTo(60_000L);
    }

    // Mockito argument helpers
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
