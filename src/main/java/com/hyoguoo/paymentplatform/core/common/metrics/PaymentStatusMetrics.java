package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStatusMetrics {

    private final MeterRegistry meterRegistry;

    public void recordStatusChange(String fromStatus, String toStatus, String trigger) {
        Counter.builder("payment_status_change.total")
                .description("Total payment status changes")
                .tag("from_status", fromStatus)
                .tag("to_status", toStatus)
                .tag("trigger", trigger)
                .register(meterRegistry)
                .increment();
    }
}
