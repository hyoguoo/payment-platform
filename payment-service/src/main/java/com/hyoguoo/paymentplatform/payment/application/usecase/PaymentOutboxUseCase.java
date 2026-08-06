package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxCreationResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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

    /**
     * 발행 실패로 트랜잭션이 롤백된 뒤, 별도 트랜잭션에서 재시도 횟수·다음 시도 시각을
     * 조건부로 기록한다 — 발행 트랜잭션의 롤백과 분리해 발행 자체의 원자성을 건드리지 않는다.
     *
     * <p>{@link Propagation#REQUIRES_NEW}로 호출자(워커)의 트랜잭션과 무관하게 새 트랜잭션을
     * 연다. 이 클래스는 워커와 다른 빈이라 프록시를 거쳐 전파 설정이 실제로 걸린다.
     *
     * <p>행이 없거나(이미 발행이 끝나 삭제됐거나 존재하지 않음) 조건부 갱신이 0건이면
     * (그 사이 다른 워커가 선점했거나 이미 같은 기록을 마쳤으면) 예외 없이 조용히 끝낸다 —
     * 둘 다 경합에서 진 정상 흐름이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPublishFailureDelay(String orderId) {
        Optional<PaymentOutbox> outboxOpt = paymentOutboxRepository.findByOrderId(orderId);
        if (outboxOpt.isEmpty()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_OUTBOX_RETRY_DELAY_SKIPPED,
                    () -> "PaymentOutboxUseCase: 기록 대상 outbox 없음, orderId=" + orderId);
            return;
        }

        PaymentOutbox outbox = outboxOpt.get();
        int expectedRetryCount = outbox.getRetryCount();
        RetryPolicy policy = retryPolicyProperties.toRetryPolicy();
        outbox.recordRetryDelay(policy, clock.instant());

        boolean updated = paymentOutboxRepository.recordRetryDelay(
                orderId, expectedRetryCount, outbox.getRetryCount(), outbox.getNextRetryAt());
        if (!updated) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_OUTBOX_RETRY_DELAY_SKIPPED,
                    () -> "PaymentOutboxUseCase: 조건부 갱신 0건(경합 패배), orderId=" + orderId);
            return;
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_OUTBOX_RETRY_DELAY_RECORDED,
                () -> "PaymentOutboxUseCase: 발행 재시도 간격 기록 완료, orderId=" + orderId
                        + " retryCount=" + outbox.getRetryCount()
                        + " nextRetryAt=" + outbox.getNextRetryAt());
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
