package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.payment.application.metrics.SchedulerMetrics;
import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentExpirationService;
import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentRecoverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final PaymentRecoverService paymentRecoverService;
    private final PaymentExpirationService paymentExpirationService;
    private final SchedulerMetrics schedulerMetrics;

    @Scheduled(fixedRateString = "${scheduler.payment-status-sync.fixed-rate:300000}")
    @ConditionalOnProperty(
            name = "scheduler.payment-status-sync.enabled",
            havingValue = "true"
    )
    public void recoverRetryablePayment() {
        schedulerMetrics.recordExecution("recovery", "started");
        try {
            paymentRecoverService.recoverRetryablePayment();
            schedulerMetrics.recordExecution("recovery", "completed");
        } catch (Exception e) {
            schedulerMetrics.recordExecution("recovery", "error");
            throw e;
        }
    }

    @Scheduled(fixedRateString = "${scheduler.payment-status-sync.fixed-rate:300000}")
    @ConditionalOnProperty(
            name = "scheduler.payment-status-sync.enabled",
            havingValue = "true"
    )
    public void expireOldReadyPayments() {
        schedulerMetrics.recordExecution("expiration", "started");
        try {
            paymentExpirationService.expireOldReadyPayments();
            schedulerMetrics.recordExecution("expiration", "completed");
        } catch (Exception e) {
            schedulerMetrics.recordExecution("expiration", "error");
            throw e;
        }
    }
}
