package com.hyoguoo.paymentplatform.payment.scheduler;

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

    @Scheduled(fixedRateString = "${scheduler.payment-status-sync.fixed-rate:300000}")
    @ConditionalOnProperty(
            name = "scheduler.payment-status-sync.enabled",
            havingValue = "true"
    )
    public void recoverRetryablePayment() {
        paymentRecoverService.recoverRetryablePayment();
    }

    @Scheduled(fixedRateString = "${scheduler.payment-status-sync.fixed-rate:300000}")
    @ConditionalOnProperty(
            name = "scheduler.payment-status-sync.enabled",
            havingValue = "true"
    )
    public void expireOldReadyPayments() {
        paymentExpirationService.expireOldReadyPayments();
    }
}
