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

    // D5 — 주키 scheduler.payment-expiration.fixed-rate, 이전 키 payment-status-sync.fixed-rate 를 fallback으로 보존(운영 무중단).
    @Scheduled(fixedRateString = "${scheduler.payment-expiration.fixed-rate:${scheduler.payment-status-sync.fixed-rate:300000}}")
    public void expireOldReadyPayments() {
        paymentExpirationService.expireOldReadyPayments();
    }
}
