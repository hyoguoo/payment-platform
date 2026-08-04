package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Reason;
import com.hyoguoo.paymentplatform.payment.core.common.aspect.annotation.Trigger;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChangeTrigger;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;
    private final PaymentQuarantineMetrics paymentQuarantineMetrics;

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "IN_PROGRESS", trigger = PaymentStatusChangeTrigger.CONFIRM)
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        Instant now = clock.instant();
        paymentEvent.execute(paymentKey, now, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "DONE", trigger = PaymentStatusChangeTrigger.CONFIRM)
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, Instant approvedAt) {
        Instant now = clock.instant();
        paymentEvent.done(approvedAt, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    /**
     * 결제 실패 전이. 승인 실패 경로({@link PaymentConfirmResultUseCase})와 재고 실패 경로
     * ({@link PaymentFailureUseCase})가 함께 호출하므로, 애노테이션 고정값 대신 호출자가
     * {@code trigger} 인자로 전이 주체를 넘긴다.
     */
    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED")
    public PaymentEvent markPaymentAsFail(
            PaymentEvent paymentEvent,
            @Reason String failureReason,
            @Trigger String trigger
    ) {
        Instant now = clock.instant();
        paymentEvent.fail(failureReason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "EXPIRED", trigger = PaymentStatusChangeTrigger.EXPIRATION)
    public PaymentEvent expirePayment(PaymentEvent paymentEvent) {
        Instant now = clock.instant();
        paymentEvent.expire(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    /**
     * 격리 전이. 재고 캐시 장애 경로({@link PaymentTransactionCoordinator})와 금액 불일치·벤더 격리
     * 경로({@link QuarantineCompensationHandler})가 함께 호출하므로, 애노테이션 고정값 대신
     * 호출자가 {@code trigger} 인자로 전이 주체를 넘긴다.
     */
    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "QUARANTINED")
    public PaymentEvent markPaymentAsQuarantined(
            PaymentEvent paymentEvent,
            @Reason String reason,
            @Trigger String trigger
    ) {
        Instant now = clock.instant();
        paymentEvent.quarantine(reason, now);
        PaymentEvent saved = paymentEventRepository.saveOrUpdate(paymentEvent);
        paymentQuarantineMetrics.recordQuarantine(reason);
        return saved;
    }

    /**
     * 격리(QUARANTINED) 결제를 관리자 안전 종결(FAILED)로 전이한다.
     * <p>
     * 도메인 {@link PaymentEvent#failFromQuarantine} 전이(in-memory) 직후, 같은 TX 안에서
     * {@link PaymentEventRepository#resolveQuarantineToFailed} CAS 조건부 UPDATE 로 영속화한다.
     * CAS 가 0건(대상이 이미 QUARANTINED 가 아님 — 동시 복구 race 패배)이면 예외를 던져 TX 를
     * 롤백시킨다 — {@code @PublishDomainEvent} 가 발행한 history 이벤트도 {@code BEFORE_COMMIT}
     * 시점에 도달하지 못해 함께 롤백된다(AOP audit 우회 방지).
     * <p>
     * redis 재고 보상은 이 메서드가 담당하지 않는다 — 호출자({@link QuarantineResolveUseCase})가
     * 이 메서드를 호출하기 전, TX 밖에서 먼저 수행한다(PITFALLS §3, 외부 호출 커넥션 점유 회피).
     *
     * @param paymentEvent QUARANTINED 상태의 결제 이벤트
     * @param reason       안전 종결 사유(필수)
     * @return 전이·저장된 결제 이벤트
     * @throws PaymentStatusException CAS 충돌 시 ({@link PaymentErrorCode#QUARANTINE_RESOLVE_CONFLICT})
     */
    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED", trigger = PaymentStatusChangeTrigger.MANUAL)
    public PaymentEvent markPaymentAsFailFromQuarantine(PaymentEvent paymentEvent, @Reason String reason) {
        Instant now = clock.instant();
        paymentEvent.failFromQuarantine(reason, now);
        boolean resolved = paymentEventRepository.resolveQuarantineToFailed(paymentEvent.getId(), reason, now);
        if (!resolved) {
            throw PaymentStatusException.of(PaymentErrorCode.QUARANTINE_RESOLVE_CONFLICT);
        }
        return paymentEvent;
    }

}
