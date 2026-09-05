package com.hyoguoo.paymentplatform.payment.core.common.metrics;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final Clock clock;
    private final Map<String, AtomicLong> healthGauges = new ConcurrentHashMap<>();
    @Value("${metrics.payment.health.thresholds.stuck-in-progress-minutes:5}")
    private long stuckInProgressMinutes;
    @Value("${metrics.payment.health.thresholds.awaiting-result-minutes:15}")
    private long awaitingResultMinutes;

    @PostConstruct
    public void init() {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.METRICS_INIT,
                () -> "component=PaymentHealthMetrics stuckInProgressMinutes=" + stuckInProgressMinutes
                        + " awaitingResultMinutes=" + awaitingResultMinutes);

        registerHealthGauge("stuck_in_progress", "Count of payments stuck in IN_PROGRESS status");
        registerHealthGauge("stuck_awaiting_result", "Count of payments stuck in AWAITING_RESULT status");
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
        Instant now = clock.instant();

        Instant stuckThreshold = now.minus(Duration.ofMinutes(stuckInProgressMinutes));
        long stuckInProgress = paymentEventRepository
                .countByStatusAndExecutedAtBefore(PaymentEventStatus.IN_PROGRESS, stuckThreshold);
        healthGauges.get("stuck_in_progress").set(stuckInProgress);

        // 앵커는 상태 변경 시각(lastStatusChangedAt) — executedAt 은 확정 진입 때 한 번만 세팅돼
        // 갱신되지 않으므로, 그걸 쓰면 방금 결과 대기로 되돌아간 건이 즉시 적체로 잡힌다.
        Instant awaitingResultThreshold = now.minus(Duration.ofMinutes(awaitingResultMinutes));
        long stuckAwaitingResult = paymentEventRepository
                .findAwaitingResultOlderThan(awaitingResultThreshold)
                .size();
        healthGauges.get("stuck_awaiting_result").set(stuckAwaitingResult);

        LogFmt.debug(log, LogDomain.PAYMENT, EventType.METRICS_GAUGE_UPDATED,
                () -> "stuckInProgress=" + stuckInProgress + " stuckAwaitingResult=" + stuckAwaitingResult);
    }
}
