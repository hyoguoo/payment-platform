package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxCreationResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.Clock;
import java.time.Instant;
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

    /**
     * 이미 있으면 조용히 넘어가는 삽입으로 발행 행을 생성한다.
     * 생성되지 않았으면(동시 재진입이든 저장 실패든) 예외로 막는다 — 구분해 부르는 것은 호출자 몫이다.
     */
    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        PaymentOutboxCreationResult result = paymentOutboxRepository.createPendingIfAbsent(orderId);
        if (result != PaymentOutboxCreationResult.CREATED) {
            throw new IllegalStateException(
                    "PaymentOutboxUseCase.createPendingRecord: orderId=" + orderId + " result=" + result);
        }
        return PaymentOutbox.createPending(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentOutbox> claimToInFlight(String orderId) {
        Instant now = clock.instant();
        boolean claimed = paymentOutboxRepository.claimToInFlight(orderId, now);
        if (!claimed) {
            return Optional.empty();
        }
        return paymentOutboxRepository.findByOrderId(orderId);
    }

    @Transactional
    public boolean incrementRetryOrFail(String orderId, PaymentOutbox currentOutbox) {
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        if (!policy.isExhausted(currentOutbox.getRetryCount())) {
            currentOutbox.incrementRetryCount(policy, clock.instant());
            paymentOutboxRepository.save(currentOutbox);
            return false;
        }
        return true;
    }

    @Transactional
    public void recoverTimedOutInFlightRecords(int timeoutMinutes) {
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        Instant now = clock.instant();
        Instant cutoff = now.minusSeconds(timeoutMinutes * 60L);
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

}
