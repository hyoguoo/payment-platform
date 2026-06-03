package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PaymentLoadUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final Clock clock;
    /** 만료 임계(분). 기본값 30분 — D4 외부화. */
    private final long timeoutMinutes;

    public PaymentLoadUseCase(
            PaymentEventRepository paymentEventRepository,
            Clock clock,
            @Value("${payment.expiration.ready-timeout-minutes:30}") long timeoutMinutes
    ) {
        this.paymentEventRepository = paymentEventRepository;
        this.clock = clock;
        this.timeoutMinutes = timeoutMinutes;
    }

    public PaymentEvent getPaymentEventByOrderId(String orderId) {
        return paymentEventRepository
                .findByOrderId(orderId)
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );
    }

    public List<PaymentEvent> getReadyPaymentsOlder() {
        // D4 — 만료 임계는 외부 설정에서 주입, 도메인은 임계를 모른다.
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(timeoutMinutes));
        return paymentEventRepository.findReadyPaymentsOlderThan(cutoff);
    }
}
