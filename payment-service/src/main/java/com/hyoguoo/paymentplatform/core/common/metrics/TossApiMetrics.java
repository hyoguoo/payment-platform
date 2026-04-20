package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossApiMetrics {

    private final MeterRegistry meterRegistry;

    public void recordTossApiCall(String operation, long durationMillis, boolean success, String errorType) {
        String status = success ? "success" : "failure";

        Counter.Builder counterBuilder = Counter.builder("toss.api.call.total")
                .description("Total Toss API calls")
                .tag("operation", operation)
                .tag("status", status);

        if (!success && errorType != null) {
            counterBuilder.tag("error_type", errorType);
        }

        counterBuilder.register(meterRegistry).increment();

        Timer.builder("toss.api.call.duration")
                .description("Toss API call duration")
                .tag("operation", operation)
                .tag("status", status)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMillis));
    }

    public void recordTossApiCall(String operation, long durationMillis, boolean success) {
        recordTossApiCall(operation, durationMillis, success, null);
    }
}
