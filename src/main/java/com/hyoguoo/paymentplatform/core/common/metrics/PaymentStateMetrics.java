package com.hyoguoo.paymentplatform.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStateMetrics {

    private final MeterRegistry meterRegistry;
    private final PaymentEventRepository paymentEventRepository;

    private final Map<PaymentEventStatus, AtomicLong> statusGauges = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("Initializing PaymentStateMetrics");

        for (PaymentEventStatus status : PaymentEventStatus.values()) {
            AtomicLong gaugeValue = new AtomicLong(0);
            statusGauges.put(status, gaugeValue);

            Gauge.builder("payment_state_current_total", gaugeValue, AtomicLong::get)
                    .description("Current count of payments in each status")
                    .tag("status", status.name())
                    .register(meterRegistry);

            log.debug("Registered status gauge for status: {}", status);
        }

        log.info("PaymentStateMetrics initialization complete");
    }

    @Scheduled(fixedDelayString = "${metrics.payment.state.polling-interval-seconds:10}000")
    public void updateStateGauges() {
        Map<PaymentEventStatus, Long> statusCounts = paymentEventRepository.countByStatus();

        for (AtomicLong gauge : statusGauges.values()) {
            gauge.set(0);
        }

        statusCounts.forEach((status, count) ->
                statusGauges.get(status).set(count)
        );

        log.debug("Updated state gauges: {}", statusCounts);
    }
}
