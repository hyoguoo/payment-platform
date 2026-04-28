package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Reason;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 이벤트 상태 전이 use-case.
 * payment-service 에서는 PG 를 HTTP 로 직접 호출하지 않는다 — pg-service 가 Kafka 연동을 전담한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCommandUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final PaymentQuarantineMetrics paymentQuarantineMetrics;

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "IN_PROGRESS", trigger = "confirm")
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.execute(paymentKey, now, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "DONE", trigger = "auto")
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.done(approvedAt, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED", trigger = "auto")
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, @Reason String failureReason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.fail(failureReason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "EXPIRED", trigger = "expiration")
    public PaymentEvent expirePayment(PaymentEvent paymentEvent) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.expire(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "RETRYING", trigger = "auto")
    public PaymentEvent markPaymentAsRetrying(PaymentEvent paymentEvent) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.toRetrying(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "QUARANTINED", trigger = "auto")
    public PaymentEvent markPaymentAsQuarantined(PaymentEvent paymentEvent, @Reason String reason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.quarantine(reason, now);
        PaymentEvent saved = paymentEventRepository.saveOrUpdate(paymentEvent);
        paymentQuarantineMetrics.recordQuarantine(reason);
        return saved;
    }


}
