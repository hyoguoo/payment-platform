package com.hyoguoo.paymentplatform.pg.infrastructure.aspect;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * pg-service 자체 소유 복제본 — 공통 library jar 를 두지 않고 각 서비스가 동일 메트릭 컴포넌트를 보유한다.
 * payment-service 의존 없음.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossApiMetrics {

    private final MeterRegistry meterRegistry;

    public void recordTossApiCall(String operation, long durationMillis, CallOutcome outcome, String errorType) {
        String status = outcome.tagValue();

        Counter.Builder counterBuilder = Counter.builder("toss.api.call.total")
                .description("Total Toss API calls")
                .tag("operation", operation)
                .tag("status", status);

        if (outcome == CallOutcome.FAILURE && errorType != null) {
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

    public void recordTossApiCall(String operation, long durationMillis, CallOutcome outcome) {
        recordTossApiCall(operation, durationMillis, outcome, null);
    }

    /**
     * 메트릭 태그 값으로 직렬화되는 호출 결과 enum.
     * 기존 boolean success 파라미터를 의미 있는 enum 으로 대체해 호출처에서 의도가 분명해지게 한다.
     */
    public enum CallOutcome {
        SUCCESS("success"),
        FAILURE("failure");

        private final String tagValue;

        CallOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
