package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentRecoverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentScheduler {

    private static final int FIXED_RATE = 1000 * 60 * 5;
    private final PaymentRecoverService paymentRecoverService;

    @Scheduled(fixedRate = FIXED_RATE)
    public void recoverRetryablePayment() {
        paymentRecoverService.recoverRetryablePayment();
    }
}
