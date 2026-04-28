package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.payment.application.port.in.PaymentExpirationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private final PaymentExpirationService paymentExpirationService;

    @Scheduled(fixedRateString = "${scheduler.payment-status-sync.fixed-rate:300000}")
    public void expireOldReadyPayments() {
        paymentExpirationService.expireOldReadyPayments();
    }
}
