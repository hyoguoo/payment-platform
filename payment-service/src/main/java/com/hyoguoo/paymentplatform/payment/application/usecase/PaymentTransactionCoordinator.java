package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 확정 TX 경계를 호출자(OutboxAsyncConfirmService)가 조립하도록 분해한 coordinator.
 *
 * <p>재고 차감은 Redis 캐시 원자 DECR(TX 외부). 성공 시에만 executeConfirmTx(@Transactional)가
 * payment_event 상태 전이 + outbox PENDING 생성을 하나의 TX에 묶는다. 실패/격리 분기는 caller가
 * 결정 — self-invocation으로 인한 @Transactional 무시 문제 제거.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionCoordinator {

    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final StockCachePort stockCachePort;
    private final PaymentConfirmPublisherPort confirmPublisher;

    /**
     * 재고 캐시 원자 DECR. TX 외부에서 실행된다(호출자 OutboxAsyncConfirmService도 TX 없음).
     *
     * @return SUCCESS: 전량 차감 성공, REJECTED: 재고 부족(decrement=false),
     *         CACHE_DOWN: Redis 호출 예외(연결 실패 등)
     */
    public StockDecrementResult decrementStock(List<PaymentOrder> paymentOrderList) {
        for (PaymentOrder order : paymentOrderList) {
            StockDecrementResult result = decrementSingleStock(order.getProductId(), order.getQuantity());
            if (result != StockDecrementResult.SUCCESS) {
                return result;
            }
        }
        return StockDecrementResult.SUCCESS;
    }

    private StockDecrementResult decrementSingleStock(Long productId, int quantity) {
        try {
            return stockCachePort.decrement(productId, quantity)
                    ? StockDecrementResult.SUCCESS
                    : StockDecrementResult.REJECTED;
        } catch (RuntimeException e) {
            log.warn("재고 캐시 장애 발생, QUARANTINED 분기로 전환 productId={} qty={}", productId, quantity, e);
            return StockDecrementResult.CACHE_DOWN;
        }
    }

    /**
     * 재고 캐시 장애(CACHE_DOWN) 분기: QUARANTINED 전이 + quarantine_compensation_pending 플래그 set.
     * ADR-13 §2-2b-3.
     *
     * <p>@Transactional: 두 번의 상태 변경(markPaymentAsQuarantined +
     * markQuarantineCompensationPending)을 단일 TX로 묶어 부분 커밋을 막는다.
     */
    @Transactional
    public PaymentEvent markStockCacheDownQuarantine(PaymentEvent paymentEvent) {
        PaymentEvent quarantined = paymentCommandUseCase.markPaymentAsQuarantined(
                paymentEvent, "재고 캐시 장애로 인한 격리");
        quarantined.markQuarantineCompensationPending();
        return quarantined;
    }

    /**
     * 재고 차감 성공 후 TX 안에서 executePayment(READY→IN_PROGRESS) + outbox PENDING을 원자 커밋한다.
     * 외부 호출자(OutboxAsyncConfirmService)가 Spring 프록시 경유로 호출하므로 self-invocation 문제 없음.
     *
     * <p>PaymentConfirmEvent 발행도 TX 내부에서 수행 — AFTER_COMMIT 리스너가 드롭되지 않도록
     * TX 동기화가 활성 상태일 때 publish한다. 리스너는 TX 커밋 직후 @Async 스레드에서 outbox relay.
     */
    @Transactional
    public PaymentEvent executeConfirmTx(PaymentEvent paymentEvent, String paymentKey, String orderId) {
        PaymentEvent inProgress = paymentCommandUseCase.executePayment(paymentEvent, paymentKey);
        paymentOutboxUseCase.createPendingRecord(orderId);
        confirmPublisher.publish(
                orderId,
                paymentEvent.getBuyerId(),
                paymentEvent.getTotalAmount(),
                paymentKey
        );
        return inProgress;
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
        PaymentEvent quarantined = paymentCommandUseCase.markPaymentAsQuarantined(paymentEvent, reason);
        quarantined.markQuarantineCompensationPending();
        return quarantined;
    }

    /**
     * D12 가드: TX 내 outbox/event 재조회 후 조건 충족 시에만 재고 복구 수행.
     * 조건: outbox.status == IN_FLIGHT AND event.status ∈ {READY, IN_PROGRESS, RETRYING}
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
        boolean eventCompensatable = freshEvent.getStatus().isCompensatableByFailureHandler();

        if (outboxInFlight && eventCompensatable) {
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

    public enum StockDecrementResult {
        SUCCESS, REJECTED, CACHE_DOWN
    }
}
