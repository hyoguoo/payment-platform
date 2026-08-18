package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.StockHoldRecoveryUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * StockHoldRecoveryWorker 단위 테스트 — Mockito 기반.
 *
 * <p>검증 대상: 배치 크기를 그대로 회수 판정에 전달, 실행마다 run 카운터 증가(스케줄러 생존
 * 확인용), 회수 판정 이후 잡음 잔량을 미회수 건수 게이지로 노출.
 */
@DisplayName("StockHoldRecoveryWorker 테스트")
class StockHoldRecoveryWorkerTest {

    private static final int BATCH_SIZE = 50;

    private StockHoldRecoveryUseCase mockUseCase;
    private StockHoldRecordRepository mockRepository;
    private SimpleMeterRegistry meterRegistry;
    private StockHoldRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        mockUseCase = Mockito.mock(StockHoldRecoveryUseCase.class);
        mockRepository = Mockito.mock(StockHoldRecordRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        worker = new StockHoldRecoveryWorker(mockUseCase, mockRepository, BATCH_SIZE, meterRegistry);
    }

    @Test
    @DisplayName("recover - 배치 크기를 그대로 회수 판정에 전달한다")
    void 회수_판정을_호출한다() {
        worker.recover();

        then(mockUseCase).should().recover(BATCH_SIZE);
    }

    @Test
    @DisplayName("recover - 실행마다 run 카운터가 1씩 증가한다")
    void 실행마다_run_카운터가_증가한다() {
        worker.recover();
        worker.recover();

        Counter runCounter = meterRegistry.find(StockHoldRecoveryWorker.RUN_COUNTER_NAME).counter();
        assertThat(runCounter).isNotNull();
        assertThat(runCounter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recover - 실행 후 잡음 잔량을 미회수 건수 게이지로 노출한다")
    void 미회수_건수를_게이지로_노출한다() {
        given(mockRepository.countNoise()).willReturn(7L);

        worker.recover();

        Gauge outstandingGauge = meterRegistry.find(StockHoldRecoveryWorker.OUTSTANDING_COUNT_GAUGE_NAME).gauge();
        assertThat(outstandingGauge).isNotNull();
        assertThat(outstandingGauge.value()).isEqualTo(7.0);
    }
}
