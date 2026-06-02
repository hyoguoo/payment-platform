package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentOutboxUseCase {

    private final PaymentOutboxRepository paymentOutboxRepository;
    private final Clock clock;
    private final RetryPolicyProperties retryPolicyProperties;

    @Transactional
    public void save(PaymentOutbox outbox) {
        paymentOutboxRepository.save(outbox);
    }

    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        PaymentOutbox outbox = PaymentOutbox.createPending(orderId);
        return paymentOutboxRepository.save(outbox);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentOutbox> claimToInFlight(String orderId) {
        LocalDateTime nowLdt = nowAsLocalDateTime();
        boolean claimed = paymentOutboxRepository.claimToInFlight(orderId, nowLdt);
        if (!claimed) {
            return Optional.empty();
        }
        return paymentOutboxRepository.findByOrderId(orderId);
    }

    @Transactional
    public boolean incrementRetryOrFail(String orderId, PaymentOutbox currentOutbox) {
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        if (!policy.isExhausted(currentOutbox.getRetryCount())) {
            currentOutbox.incrementRetryCount(policy, nowAsLocalDateTime());
            paymentOutboxRepository.save(currentOutbox);
            return false;
        }
        return true;
    }

    @Transactional
    public void recoverTimedOutInFlightRecords(int timeoutMinutes) {
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        LocalDateTime now = nowAsLocalDateTime();
        LocalDateTime cutoff = now.minusMinutes(timeoutMinutes);
        List<PaymentOutbox> timedOut = paymentOutboxRepository.findTimedOutInFlight(cutoff);
        for (PaymentOutbox outbox : timedOut) {
            outbox.incrementRetryCount(policy, now);
            paymentOutboxRepository.save(outbox);
        }
    }

    public List<PaymentOutbox> findPendingBatch(int batchSize) {
        return paymentOutboxRepository.findPendingBatch(batchSize);
    }

    public Optional<PaymentOutbox> findByOrderId(String orderId) {
        return paymentOutboxRepository.findByOrderId(orderId);
    }

    public Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId) {
        return paymentOutboxRepository.findByOrderId(orderId)
                .filter(outbox -> outbox.getStatus().isClaimable()
                        || outbox.getStatus().isInFlight())
                .map(PaymentOutbox::getStatus);
    }

    /**
     * Clock 으로 현재 시각을 UTC LocalDateTime 으로 변환.
     * PaymentOutboxRepository 포트가 LocalDateTime 을 받으므로 변환이 필요하다.
     * 포트 시그니처 전환은 T12 이후 별도 리팩토링에서 처리한다.
     */
    private LocalDateTime nowAsLocalDateTime() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
