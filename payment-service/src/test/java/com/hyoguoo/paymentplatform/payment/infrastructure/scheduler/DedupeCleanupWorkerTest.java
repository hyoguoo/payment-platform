package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * DedupeCleanupWorker 단위 테스트 — Mockito 기반.
 *
 * <p>검증 대상:
 * <ul>
 *   <li>deleteExpired 1회 호출 + Micrometer counter 증가</li>
 *   <li>deleteExpired 예외 시 예외 전파 없이 다음 주기 재시도</li>
 *   <li>deleteExpired 예외 시 cleanup_failed_total 카운터 1 증가, cleanup_deleted_total 0 유지</li>
 *   <li>정상 수행 시 cleanup_failed_total 카운터 미증가</li>
 * </ul>
 */
@DisplayName("DedupeCleanupWorker 테스트")
class DedupeCleanupWorkerTest {

    private static final String COUNTER_NAME = "payment_event_dedupe.cleanup_deleted_total";
    private static final String FAILED_COUNTER_NAME = "payment_event_dedupe.cleanup_failed_total";

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private PaymentEventDedupeStore mockDedupeStore;
    private SimpleMeterRegistry meterRegistry;
    private DedupeCleanupWorker worker;

    @BeforeEach
    void setUp() {
        mockDedupeStore = Mockito.mock(PaymentEventDedupeStore.class);
        meterRegistry = new SimpleMeterRegistry();

        worker = new DedupeCleanupWorker(mockDedupeStore, FIXED_CLOCK, 1000, meterRegistry);
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 5를 반환하면 counter가 5 증가한다")
    void cleanup_삭제행수_카운터증가() {
        // given
        given(mockDedupeStore.deleteExpired(any(Instant.class), anyInt())).willReturn(5);

        // when
        worker.cleanup();

        // then
        then(mockDedupeStore).should(times(1)).deleteExpired(any(Instant.class), anyInt());

        Counter counter = meterRegistry.find(COUNTER_NAME).counter();
        assertThatCode(() -> {
            assert counter != null;
            assert counter.count() == 5.0;
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 RuntimeException을 던져도 예외가 밖으로 전파되지 않는다")
    void cleanup_예외발생시_전파하지않음_다음주기재시도() {
        // given
        given(mockDedupeStore.deleteExpired(any(Instant.class), anyInt()))
                .willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        // when & then
        assertThatCode(() -> worker.cleanup()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 RuntimeException을 던지면 cleanup_failed_total 카운터가 1 증가하고 cleanup_deleted_total은 0이다")
    void cleanup_예외발생시_failedCounter증가() {
        // given
        given(mockDedupeStore.deleteExpired(any(Instant.class), anyInt()))
                .willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        // when
        worker.cleanup();

        // then
        Counter failedCounter = meterRegistry.find(FAILED_COUNTER_NAME).counter();
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(1.0);

        Counter deletedCounter = meterRegistry.find(COUNTER_NAME).counter();
        assertThat(deletedCounter).isNotNull();
        assertThat(deletedCounter.count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 정상 반환하면 cleanup_failed_total 카운터는 0이다")
    void cleanup_정상수행시_failedCounter미증가() {
        // given
        given(mockDedupeStore.deleteExpired(any(Instant.class), anyInt())).willReturn(5);

        // when
        worker.cleanup();

        // then
        Counter failedCounter = meterRegistry.find(FAILED_COUNTER_NAME).counter();
        assertThat(failedCounter).isNotNull();
        assertThat(failedCounter.count()).isEqualTo(0.0);

        Counter deletedCounter = meterRegistry.find(COUNTER_NAME).counter();
        assertThat(deletedCounter).isNotNull();
        assertThat(deletedCounter.count()).isEqualTo(5.0);
    }
}
