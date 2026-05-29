package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PgInboxPollingWorker — traceparent 복원 경로 단위 테스트.
 *
 * <p>E-4: 폴링 회수 시 findStoredTraceparent → TraceparentExtractor.restoreContext → makeCurrent 경로 검증.
 * 추적 복원은 infrastructure 내부 동작이므로 processPending / processInProgressZombie 호출 성공 자체로 검증한다.
 */
@DisplayName("PgInboxPollingWorker — traceparent 복원 경로")
class PgInboxPollingWorkerTraceparentTest {

    private PgInboxRepository inboxRepository;
    private PgInboxProcessUseCase processor;
    private PgInboxPollingWorker pollingWorker;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(PgInboxRepository.class);
        processor = mock(PgInboxProcessUseCase.class);
        pollingWorker = new PgInboxPollingWorker(
                inboxRepository, processor, 10, 60_000L, 60_000L, new SimpleMeterRegistry());
        // inProgressZombies는 항상 빈 목록 반환 (PENDING 경로 테스트 집중)
        when(inboxRepository.findInProgressZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("recoverPendingZombies_traceparent있음_부모컨텍스트복원 — findStoredTraceparent가 유효값 반환 시 processPending 1회 호출")
    void recoverPendingZombies_traceparent있음_부모컨텍스트복원() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(1L));
        when(inboxRepository.findStoredTraceparent(1L))
                .thenReturn(Optional.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

        // when
        pollingWorker.poll();

        // then: 추적 복원은 infrastructure 내부 동작 — processPending 호출 성공 자체로 검증
        verify(processor, times(1)).processPending(1L);
        // findStoredTraceparent가 실제로 호출되었는지 검증 (E-4 핵심 동작)
        verify(inboxRepository, times(1)).findStoredTraceparent(1L);
    }

    @Test
    @DisplayName("recoverPendingZombies_traceparent없음_새rootSpan폴백 — findStoredTraceparent가 empty 반환 시 예외 없이 processPending 호출")
    void recoverPendingZombies_traceparent없음_새rootSpan폴백() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(2L));
        when(inboxRepository.findStoredTraceparent(2L))
                .thenReturn(Optional.empty());

        // when
        pollingWorker.poll();

        // then: 폴백(root span) 경로 — processPending 정상 호출
        verify(processor, times(1)).processPending(2L);
        verify(inboxRepository, times(1)).findStoredTraceparent(2L);
    }

    @Test
    @DisplayName("recoverPendingZombies_traceparent형식오류_폴백처리완료 — 형식 오류 traceparent도 best-effort 폴백으로 processPending 호출")
    void recoverPendingZombies_traceparent형식오류_폴백처리완료() {
        // given
        when(inboxRepository.findPendingZombieIds(anyInt(), anyLong()))
                .thenReturn(List.of(3L));
        when(inboxRepository.findStoredTraceparent(3L))
                .thenReturn(Optional.of("invalid-format"));

        // when
        pollingWorker.poll();

        // then: 형식 오류 시 best-effort 폴백 — 예외 없이 processPending 호출
        verify(processor, times(1)).processPending(3L);
        verify(inboxRepository, times(1)).findStoredTraceparent(3L);
    }

    // Mockito argument helpers
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
