package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.StockHoldRecoveryUseCase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미회수 선차감 기록 회수 판정 주기 작업.
 *
 * <p>{@link StockHoldRecoveryUseCase#recover(int)}를 주기적으로 호출한다. run 카운터는 이 worker가
 * 실제로 주기 실행되는지 확인하는 생존 신호다 — 이 프로젝트는 스케줄러 활성화 플래그가 빠져
 * worker 코드는 정상이어도 한 번도 기동하지 않은 이력이 있어, 코드 존재가 아니라 실행 자체를
 * 관측해야 한다. 미회수 건수 게이지는 매 실행 직후 잡음(NOISE) 잔량을 그대로 노출한다.
 */
@Component
public class StockHoldRecoveryWorker {

    static final String RUN_COUNTER_NAME = "stock_hold_recovery.run_total";
    static final String OUTSTANDING_COUNT_GAUGE_NAME = "stock_hold_recovery.outstanding_count";

    private final StockHoldRecoveryUseCase stockHoldRecoveryUseCase;
    private final StockHoldRecordRepository stockHoldRecordRepository;
    private final int batchSize;
    private final Counter runCounter;
    private final AtomicLong outstandingCount = new AtomicLong(0L);

    public StockHoldRecoveryWorker(
            StockHoldRecoveryUseCase stockHoldRecoveryUseCase,
            StockHoldRecordRepository stockHoldRecordRepository,
            @Value("${scheduler.stock-hold-recovery-worker.batch-size:100}") int batchSize,
            MeterRegistry meterRegistry) {
        this.stockHoldRecoveryUseCase = stockHoldRecoveryUseCase;
        this.stockHoldRecordRepository = stockHoldRecordRepository;
        this.batchSize = batchSize;
        this.runCounter = Counter.builder(RUN_COUNTER_NAME)
                .description("StockHoldRecoveryWorker 실행 횟수 — 스케줄러 생존 확인용")
                .register(meterRegistry);
        Gauge.builder(OUTSTANDING_COUNT_GAUGE_NAME, outstandingCount, AtomicLong::doubleValue)
                .description("잡음(NOISE) 상태로 남은 선차감 기록 수 — 미회수 잔량")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${scheduler.stock-hold-recovery-worker.fixed-delay-ms:60000}")
    public void recover() {
        runCounter.increment();
        stockHoldRecoveryUseCase.recover(batchSize);
        outstandingCount.set(stockHoldRecordRepository.countNoise());
    }
}
