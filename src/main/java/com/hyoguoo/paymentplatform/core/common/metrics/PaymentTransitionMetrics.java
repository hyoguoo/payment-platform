package com.hyoguoo.paymentplatform.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTransitionMetrics {

    private final MeterRegistry meterRegistry;

    private final Map<String, Counter> transitionCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> transitionTimers = new ConcurrentHashMap<>();

    public void recordTransition(
            String fromStatus,
            String toStatus,
            String trigger,
            boolean success,
            Duration duration
    ) {
        String result = success ? "success" : "failure";
        String counterKey = String.format("%s:%s:%s:%s", fromStatus, toStatus, trigger, result);

        Counter counter = transitionCounters.computeIfAbsent(counterKey, key ->
                Counter.builder("payment_transition_total")
                        .description("Total number of payment state transitions")
                        .tag("from_status", fromStatus)
                        .tag("to_status", toStatus)
                        .tag("trigger", trigger)
                        .tag("result", result)
                        .register(meterRegistry)
        );
        counter.increment();

        // Record transition duration (only for successful transitions)
        if (success && duration != null) {
            String timerKey = String.format("%s:%s", fromStatus, toStatus);
            Timer timer = transitionTimers.computeIfAbsent(timerKey, key ->
                    Timer.builder("payment_transition_duration_seconds")
                            .description("Duration of payment state transitions")
                            .tag("from_status", fromStatus)
                            .tag("to_status", toStatus)
                            .register(meterRegistry)
            );
            timer.record(duration);
        }

        log.debug("Recorded payment transition: {} -> {} (trigger: {}, result: {}, duration: {})",
                fromStatus, toStatus, trigger, result, duration);
    }
}
