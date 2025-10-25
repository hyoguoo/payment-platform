package com.hyoguoo.paymentplatform.payment.application.metrics;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStatusGaugeMetrics {

    private final MeterRegistry meterRegistry;
    private final PaymentEventRepository paymentEventRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    private final Map<PaymentEventStatus, AtomicLong> statusGauges = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong longInProgressCount = new AtomicLong(0);
    private final AtomicLong unknownCount = new AtomicLong(0);
    private final AtomicLong maxRetryCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        log.info("Initializing Payment Status Gauge Metrics");

        for (PaymentEventStatus status : PaymentEventStatus.values()) {
            AtomicLong count = new AtomicLong(0);
            statusGauges.put(status, count);

            Gauge.builder("payment_status_current.count", count, AtomicLong::get)
                    .description("Current count of payments by status")
                    .tag("status", status.name())
                    .register(meterRegistry);
        }

        Gauge.builder("payment_dangerous.count", longInProgressCount, AtomicLong::get)
                .description("Count of payments in dangerous state")
                .tag("type", "long_in_progress")
                .register(meterRegistry);

        Gauge.builder("payment_dangerous.count", unknownCount, AtomicLong::get)
                .description("Count of payments in dangerous state")
                .tag("type", "unknown")
                .register(meterRegistry);

        Gauge.builder("payment_dangerous.count", maxRetryCount, AtomicLong::get)
                .description("Count of payments in dangerous state")
                .tag("type", "max_retry")
                .register(meterRegistry);

        log.info("Payment Status Gauge Metrics initialized - gauge types (payment_status_current, payment_dangerous)");
    }

    @Scheduled(fixedRate = 60000)
    public void updateStatusGauges() {
        try {
            Map<PaymentEventStatus, Long> statusCounts = paymentEventRepository.countByStatus();
            for (PaymentEventStatus status : PaymentEventStatus.values()) {
                long count = statusCounts.getOrDefault(status, 0L);
                statusGauges.get(status).set(count);
            }

            LocalDateTime fiveMinutesAgo = localDateTimeProvider.now().minusMinutes(5);
            long longInProgress = paymentEventRepository
                    .countByStatusAndExecutedAtBefore(PaymentEventStatus.IN_PROGRESS, fiveMinutesAgo);
            longInProgressCount.set(longInProgress);

            long unknown = statusCounts.getOrDefault(PaymentEventStatus.UNKNOWN, 0L);
            unknownCount.set(unknown);

            long maxRetry = paymentEventRepository.countByRetryCountGreaterThanEqual(5);
            maxRetryCount.set(maxRetry);

            log.debug("Payment status gauges updated - longInProgress={}, unknown={}, maxRetry={}",
                    longInProgress, unknown, maxRetry);
        } catch (Exception e) {
            log.error("Failed to update payment status gauges", e);
        }
    }
}
