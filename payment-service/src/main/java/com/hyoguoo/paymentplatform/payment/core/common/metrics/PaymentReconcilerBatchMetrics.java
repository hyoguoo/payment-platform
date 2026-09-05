package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 리컨실러 배치의 항목별 격리 결과를 집계한다.
 *
 * <p>조건부 전이가 0건으로 끝나는 것(그 사이 확정된 건과의 경합)은 배치가 크게 잡히는 부하
 * 구간에서 흔히 일어나는 정상 동작이라 카운터만 남기고 경고 로그를 찍지 않는다 — 경고로 남기면
 * 로그가 폭주하고 지표가 오염된다. 위임 메서드 호출 자체가 실패하는 것(예외)만 만료 배치와 같은
 * 경고 + 카운터로 남긴다 — 기동 즉시 0 시리즈를 노출하도록 생성자에서 eager 등록한다.
 */
@Slf4j
@Component
public class PaymentReconcilerBatchMetrics {

    private final Counter raceSkippedCounter;
    private final Counter itemFailedCounter;

    public PaymentReconcilerBatchMetrics(MeterRegistry meterRegistry) {
        this.raceSkippedCounter = Counter.builder("payment_reconciler_race_skipped_total")
                .description("Total items skipped by reconciler batch due to conditional "
                        + "transition losing the race to a concurrent confirm")
                .register(meterRegistry);
        this.itemFailedCounter = Counter.builder("payment_reconciler_item_failed_total")
                .description("Total items skipped by reconciler batch due to per-item failure")
                .register(meterRegistry);
    }

    public void recordRaceSkip(String orderId) {
        raceSkippedCounter.increment();
        LogFmt.debug(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED, () ->
                String.format("Reconciler race skip - orderId=%s", orderId));
    }

    public void recordFailure(String orderId, Throwable cause) {
        itemFailedCounter.increment();
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_ITEM_FAILED, () ->
                String.format("Reconciler batch item failed (isolated) - orderId=%s, cause=%s",
                        orderId, cause.getMessage()));
    }
}
