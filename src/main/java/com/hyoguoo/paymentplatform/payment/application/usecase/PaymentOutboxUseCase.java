package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.LocalDateTime;
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
    private final LocalDateTimeProvider localDateTimeProvider;
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
        boolean claimed = paymentOutboxRepository.claimToInFlight(orderId, localDateTimeProvider.now());
        if (!claimed) {
            return Optional.empty();
        }
        return paymentOutboxRepository.findByOrderId(orderId);
    }

    @Transactional
    public boolean incrementRetryOrFail(String orderId, PaymentOutbox currentOutbox) {
        RetryPolicy policy = buildRetryPolicy();
        if (!policy.isExhausted(currentOutbox.getRetryCount())) {
            currentOutbox.incrementRetryCount(policy, localDateTimeProvider.now());
            paymentOutboxRepository.save(currentOutbox);
            return false;
        }
        return true;
    }

    @Transactional
    public void recoverTimedOutInFlightRecords(int timeoutMinutes) {
        RetryPolicy policy = buildRetryPolicy();
        LocalDateTime now = localDateTimeProvider.now();
        LocalDateTime cutoff = now.minusMinutes(timeoutMinutes);
        List<PaymentOutbox> timedOut = paymentOutboxRepository.findTimedOutInFlight(cutoff);
        for (PaymentOutbox outbox : timedOut) {
            outbox.incrementRetryCount(policy, now);
            paymentOutboxRepository.save(outbox);
        }
    }

    private RetryPolicy buildRetryPolicy() {
        return new RetryPolicy(
                retryPolicyProperties.getMaxAttempts(),
                retryPolicyProperties.getBackoffType(),
                retryPolicyProperties.getBaseDelayMs(),
                retryPolicyProperties.getMaxDelayMs()
        );
    }

    public List<PaymentOutbox> findPendingBatch(int batchSize) {
        return paymentOutboxRepository.findPendingBatch(batchSize);
    }

    public Optional<PaymentOutbox> findByOrderId(String orderId) {
        return paymentOutboxRepository.findByOrderId(orderId);
    }

    public Optional<PaymentOutboxStatus> findActiveOutboxStatus(String orderId) {
        return paymentOutboxRepository.findByOrderId(orderId)
                .filter(outbox -> outbox.getStatus() == PaymentOutboxStatus.PENDING
                        || outbox.getStatus() == PaymentOutboxStatus.IN_FLIGHT)
                .map(PaymentOutbox::getStatus);
    }
}
