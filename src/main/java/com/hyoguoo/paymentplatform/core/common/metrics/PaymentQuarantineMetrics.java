package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentQuarantineMetrics {

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> quarantineCounters = new ConcurrentHashMap<>();

    public void recordQuarantine(String reason) {
        Counter counter = quarantineCounters.computeIfAbsent(reason, key ->
                Counter.builder("payment_quarantined_total")
                        .description("Total number of payments moved to QUARANTINED status")
                        .tag("reason", key)
                        .register(meterRegistry)
        );
        counter.increment();

        log.debug("Recorded payment quarantine: reason={}", reason);
    }
}
