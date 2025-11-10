package com.hyoguoo.paymentplatform.core.common.metrics;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentHealthMetrics {

    private final MeterRegistry meterRegistry;
    private final PaymentEventRepository paymentEventRepository;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final Map<String, AtomicLong> healthGauges = new ConcurrentHashMap<>();
    @Value("${metrics.payment.health.thresholds.stuck-in-progress-minutes:5}")
    private long stuckInProgressMinutes;
    @Value("${metrics.payment.health.thresholds.max-retry-count:5}")
    private int maxRetryCount;

    @PostConstruct
    public void init() {
        log.info("Initializing PaymentHealthMetrics with thresholds - stuckInProgressMinutes={}, maxRetryCount={}",
                stuckInProgressMinutes, maxRetryCount);

        registerHealthGauge("stuck_in_progress", "Count of payments stuck in IN_PROGRESS status");
        registerHealthGauge("unknown_status", "Count of payments in UNKNOWN status");
        registerHealthGauge("max_retry_reached", "Count of payments that reached max retry count");

        log.info("PaymentHealthMetrics initialization complete");
    }

    private void registerHealthGauge(String type, String description) {
        AtomicLong gaugeValue = new AtomicLong(0);
        healthGauges.put(type, gaugeValue);

        Gauge.builder("payment_health_" + type + "_total", gaugeValue, AtomicLong::get)
                .description(description)
                .register(meterRegistry);

        log.debug("Registered health gauge: payment_health_{}_total", type);
    }

    @Scheduled(fixedDelayString = "${metrics.payment.health.polling-interval-seconds:10}000")
    public void updateHealthGauges() {
        LocalDateTime now = localDateTimeProvider.now();

        // Stuck in progress
        LocalDateTime stuckThreshold = now.minusMinutes(stuckInProgressMinutes);
        long stuckInProgress = paymentEventRepository
                .countByStatusAndExecutedAtBefore(PaymentEventStatus.IN_PROGRESS, stuckThreshold);
        healthGauges.get("stuck_in_progress").set(stuckInProgress);

        // Unknown status
        long unknownStatus = paymentEventRepository.countByStatus()
                .getOrDefault(PaymentEventStatus.UNKNOWN, 0L);
        healthGauges.get("unknown_status").set(unknownStatus);

        // Max retry reached
        long maxRetryReached = paymentEventRepository
                .countByRetryCountGreaterThanEqual(maxRetryCount);
        healthGauges.get("max_retry_reached").set(maxRetryReached);

        log.debug("Health gauges updated - stuckInProgress={}, unknownStatus={}, maxRetryReached={}",
                stuckInProgress, unknownStatus, maxRetryReached);
    }
}
