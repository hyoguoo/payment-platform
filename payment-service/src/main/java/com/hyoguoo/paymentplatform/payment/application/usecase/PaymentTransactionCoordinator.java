package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionCoordinator {

    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;

    @Transactional(rollbackFor = PaymentOrderedProductStockException.class)
    public PaymentEvent executePaymentAndStockDecreaseWithOutbox(
            PaymentEvent paymentEvent,
            String paymentKey,
            String orderId,
            List<PaymentOrder> paymentOrderList
    ) throws PaymentOrderedProductStockException {
        PaymentEvent inProgressEvent = paymentCommandUseCase.executePayment(paymentEvent, paymentKey);
        orderedProductUseCase.decreaseStockForOrders(paymentOrderList);
        paymentOutboxUseCase.createPendingRecord(orderId);
        return inProgressEvent;
    }

    @Transactional
    public PaymentEvent executePaymentSuccessCompletionWithOutbox(
            PaymentEvent paymentEvent,
            LocalDateTime approvedAt,
            PaymentOutbox outbox
    ) {
        outbox.toDone();
        paymentOutboxUseCase.save(outbox);
        return paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);
    }

    @Transactional
    public PaymentEvent executePaymentRetryWithOutbox(
            PaymentEvent paymentEvent,
            PaymentOutbox outbox,
            RetryPolicy policy,
            LocalDateTime now
    ) {
        outbox.incrementRetryCount(policy, now);
        paymentOutboxUseCase.save(outbox);
        return paymentCommandUseCase.markPaymentAsRetrying(paymentEvent);
    }

    @Transactional
    public PaymentEvent executePaymentQuarantineWithOutbox(
            PaymentEvent paymentEvent,
            PaymentOutbox outbox,
            String reason
    ) {
        outbox.toFailed();
        paymentOutboxUseCase.save(outbox);
        return paymentCommandUseCase.markPaymentAsQuarantined(paymentEvent, reason);
    }

    /**
     * D12 가드: TX 내 outbox/event 재조회 후 조건 충족 시에만 재고 복구 수행.
     * 조건: outbox.status == IN_FLIGHT AND event.status ∈ {READY, IN_PROGRESS, RETRYING}
     * 어느 하나라도 거짓이면 재고 복구를 건너뜀. markPaymentAsFail은 항상 호출되나, fail() no-op으로 이미 종결 시 상태 불변.
     */
    @Transactional
    public PaymentEvent executePaymentFailureCompensationWithOutbox(
            String orderId,
            List<PaymentOrder> paymentOrderList,
            String failureReason
    ) {
        PaymentOutbox freshOutbox = paymentOutboxUseCase.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Outbox not found for orderId: " + orderId));
        PaymentEvent freshEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        boolean outboxInFlight = freshOutbox.getStatus() == PaymentOutboxStatus.IN_FLIGHT;
        boolean eventNonTerminal = !freshEvent.getStatus().isTerminal();

        if (outboxInFlight && eventNonTerminal) {
            orderedProductUseCase.increaseStockForOrders(paymentOrderList);
        } else {
            log.warn(
                    "D12 guard: 재고 복구 건너뜀 orderId={} outboxStatus={} eventStatus={}",
                    orderId, freshOutbox.getStatus(), freshEvent.getStatus()
            );
        }

        if (outboxInFlight) {
            freshOutbox.toFailed();
            paymentOutboxUseCase.save(freshOutbox);
        }

        return paymentCommandUseCase.markPaymentAsFail(freshEvent, failureReason);
    }
}
