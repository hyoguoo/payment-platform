package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerMetrics {

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void init() {
        log.info("Initializing Scheduler Metrics");

        Counter.builder("scheduler_execution.total")
                .description("Total scheduler executions")
                .tag("type", "unknown")
                .tag("result", "unknown")
                .register(meterRegistry);

        log.info("Scheduler Metrics initialized - 1 metric (scheduler_execution.total)");
    }

    public void recordExecution(String type, String result) {
        Counter.builder("scheduler_execution.total")
                .description("Total scheduler executions")
                .tag("type", type)
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
