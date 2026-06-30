package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 만료 배치에서 개별 결제 만료가 실패해 건너뛴 횟수를 집계한다.
 *
 * <p>만료 배치는 건별 독립 트랜잭션으로 처리되며, 한 건의 실패(예: order 가 EXECUTING 으로
 * 잔류해 expire 가드를 통과 못하는 stranded READY)는 격리되어 나머지 정상 건 만료를 막지 않는다.
 * 격리된 실패가 silent 하지 않도록 이 카운터로 가시화한다 — 기동 즉시 0 시리즈를 노출하도록
 * 생성자에서 eager 등록한다.
 */
@Slf4j
@Component
public class PaymentExpirationSkipMetrics {

    private final Counter skippedCounter;

    public PaymentExpirationSkipMetrics(MeterRegistry meterRegistry) {
        this.skippedCounter = Counter.builder("payment_expiration_skipped_total")
                .description("Total READY payments skipped during expiration batch due to per-item failure")
                .register(meterRegistry);
    }

    public void recordSkip(String orderId, Throwable cause) {
        skippedCounter.increment();
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_EXPIRATION_SKIPPED, () ->
                String.format("Payment expiration skipped (isolated failure) - orderId=%s, cause=%s",
                        orderId, cause.getMessage()));
    }
}
