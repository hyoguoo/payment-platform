package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerMetrics {

    private final MeterRegistry meterRegistry;

    public void recordExecution(String type, String result) {
        Counter.builder("scheduler_execution.total")
                .description("Total scheduler executions")
                .tag("type", type)
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
