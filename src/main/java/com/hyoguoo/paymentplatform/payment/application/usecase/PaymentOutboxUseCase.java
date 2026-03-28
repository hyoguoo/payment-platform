package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
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

    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        PaymentOutbox outbox = PaymentOutbox.createPending(orderId);
        return paymentOutboxRepository.save(outbox);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimToInFlight(PaymentOutbox outbox) {
        try {
            outbox.toInFlight(localDateTimeProvider.now());
            paymentOutboxRepository.save(outbox);
            return true;
        } catch (PaymentStatusException e) {
            return false;
        }
    }

    @Transactional
    public void markDone(String orderId) {
        PaymentOutbox outbox = paymentOutboxRepository.findByOrderId(orderId)
                .orElseThrow(() -> com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException.of(
                        PaymentErrorCode.PAYMENT_OUTBOX_NOT_FOUND));
        if (outbox.getStatus() == PaymentOutboxStatus.DONE) {
            return;
        }
        outbox.toDone();
        paymentOutboxRepository.save(outbox);
    }

    @Transactional
    public void markFailed(String orderId) {
        PaymentOutbox outbox = paymentOutboxRepository.findByOrderId(orderId)
                .orElseThrow(() -> com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException.of(
                        PaymentErrorCode.PAYMENT_OUTBOX_NOT_FOUND));
        if (outbox.getStatus() == PaymentOutboxStatus.FAILED) {
            return;
        }
        outbox.toFailed();
        paymentOutboxRepository.save(outbox);
    }

    @Transactional
    public void incrementRetryOrFail(String orderId, PaymentOutbox currentOutbox) {
        if (currentOutbox.isRetryable()) {
            currentOutbox.incrementRetryCount();
            paymentOutboxRepository.save(currentOutbox);
        } else {
            markFailed(orderId);
        }
    }

    @Transactional
    public void recoverTimedOutInFlightRecords(int timeoutMinutes) {
        LocalDateTime cutoff = localDateTimeProvider.now().minusMinutes(timeoutMinutes);
        List<PaymentOutbox> timedOut = paymentOutboxRepository.findTimedOutInFlight(cutoff);
        for (PaymentOutbox outbox : timedOut) {
            outbox.incrementRetryCount();
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
                .filter(outbox -> outbox.getStatus() == PaymentOutboxStatus.PENDING
                        || outbox.getStatus() == PaymentOutboxStatus.IN_FLIGHT)
                .map(PaymentOutbox::getStatus);
    }
}
