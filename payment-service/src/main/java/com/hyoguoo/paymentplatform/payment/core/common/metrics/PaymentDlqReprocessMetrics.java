package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DLQ({@code events.confirmed.dlq}) 재주입 이력 계측 — 결과(result)별 시도 횟수를 태그로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentDlqReprocessMetrics {

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> reprocessCounters = new ConcurrentHashMap<>();

    public void recordReprocess(String result) {
        Counter counter = reprocessCounters.computeIfAbsent(result, key ->
                Counter.builder("payment_dlq_reprocess_total")
                        .description("Total number of DLQ reprocess attempts by result")
                        .tag("result", key)
                        .register(meterRegistry)
        );
        counter.increment();

        LogFmt.debug(log, LogDomain.PAYMENT, EventType.METRICS_RECORDED,
                () -> "metric=payment_dlq_reprocess_total result=" + result);
    }
}
