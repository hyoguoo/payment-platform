package com.hyoguoo.paymentplatform.payment.application.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStatusMetrics {

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        log.info("Initializing Payment Status Metrics");

        Counter.builder("payment_status_change.total")
                .description("Total payment status changes")
                .tag("from_status", "UNKNOWN")
                .tag("to_status", "UNKNOWN")
                .tag("trigger", "UNKNOWN")
                .register(meterRegistry);

        log.info("Payment Status Metrics initialized - 1 metric (payment_status_change.total)");
    }

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
