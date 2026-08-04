package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxCreationResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOutboxDuplicateException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
     *
     * <p>동시 재진입(ALREADY_EXISTS)은 {@link PaymentOutboxDuplicateException}으로, 그 밖의
     * 저장 실패(SAVE_FAILED)는 {@link IllegalStateException}으로 갈라 던진다 — 호출자가 둘을
     * 구분해 전자만 재고 미회수 경보에서 뺄 수 있어야 한다.
     */
    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        PaymentOutboxCreationResult result = paymentOutboxRepository.createPendingIfAbsent(orderId);
        return switch (result) {
            case CREATED -> PaymentOutbox.createPending(orderId);
            case ALREADY_EXISTS -> throw PaymentOutboxDuplicateException.of(
                    PaymentErrorCode.PAYMENT_OUTBOX_DUPLICATE_INSERT);
            case SAVE_FAILED -> throw new IllegalStateException(
                    "PaymentOutboxUseCase.createPendingRecord: orderId=" + orderId + " result=" + result);
        };
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
