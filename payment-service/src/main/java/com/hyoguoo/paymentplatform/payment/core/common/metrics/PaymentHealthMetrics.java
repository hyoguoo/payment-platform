package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
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
        LogFmt.info(log, LogDomain.PAYMENT, EventType.METRICS_INIT,
                () -> "component=PaymentHealthMetrics stuckInProgressMinutes=" + stuckInProgressMinutes
                        + " maxRetryCount=" + maxRetryCount);

        registerHealthGauge("stuck_in_progress", "Count of payments stuck in IN_PROGRESS status");
        registerHealthGauge("max_retry_reached", "Count of payments that reached max retry count");
    }

    private void registerHealthGauge(String type, String description) {
        AtomicLong gaugeValue = new AtomicLong(0);
        healthGauges.put(type, gaugeValue);

        Gauge.builder("payment_health_" + type + "_total", gaugeValue, AtomicLong::get)
                .description(description)
                .register(meterRegistry);

        LogFmt.debug(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_REGISTERED,
                () -> "gauge=payment_health_" + type + "_total");
    }

    @Scheduled(fixedDelayString = "${metrics.payment.health.polling-interval-seconds:10}000")
    public void updateHealthGauges() {
        LocalDateTime now = localDateTimeProvider.now();

        LocalDateTime stuckThreshold = now.minusMinutes(stuckInProgressMinutes);
        long stuckInProgress = paymentEventRepository
                .countByStatusAndExecutedAtBefore(PaymentEventStatus.IN_PROGRESS, stuckThreshold);
        healthGauges.get("stuck_in_progress").set(stuckInProgress);

        long maxRetryReached = paymentEventRepository
                .countByRetryCountGreaterThanEqual(maxRetryCount);
        healthGauges.get("max_retry_reached").set(maxRetryReached);

        LogFmt.debug(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_UPDATED,
                () -> "stuckInProgress=" + stuckInProgress + " maxRetryReached=" + maxRetryReached);
    }
}
