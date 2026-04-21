package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
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
    private final StockCachePort stockCachePort;

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
        PaymentEvent quarantined = paymentCommandUseCase.markPaymentAsQuarantined(paymentEvent, reason);
        // ADR-13 §2-2b-3: QUARANTINED 전이 시 항상 2단계 복구 대기 플래그 set
        quarantined.markQuarantineCompensationPending();
        return quarantined;
    }

    /**
     * T1-05: 재고 캐시 차감 → 성공 시 @Transactional 블록(event 전이 + outbox 생성) 진입.
     * TX 경계: stockCachePort.decrement()는 TX 외부에서 호출됨.
     * - decrement=false(재고 부족): FAILED 전이, outbox 미생성.
     * - decrement 예외(cache 장애): QUARANTINED 전이, outbox 미생성, quarantine_compensation_pending=true.
     * - decrement=true: TX 진입 → executePayment + createPendingRecord (원자적 커밋).
     */
    public PaymentEvent executePaymentConfirm(
            PaymentEvent paymentEvent,
            String paymentKey,
            String orderId,
            List<PaymentOrder> paymentOrderList
    ) {
        StockDecrementResult decrementResult = decrementAllStock(paymentOrderList);
        if (decrementResult == StockDecrementResult.REJECTED) {
            return paymentCommandUseCase.markPaymentAsFail(paymentEvent, "재고 부족으로 인한 결제 실패");
        }
        if (decrementResult == StockDecrementResult.CACHE_DOWN) {
            return quarantineForCacheFailure(paymentEvent);
        }
        return executePaymentConfirmInTransaction(paymentEvent, paymentKey, orderId);
    }

    private StockDecrementResult decrementAllStock(List<PaymentOrder> paymentOrderList) {
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
            // cache 장애 분기: Redis down 등 예외 → QUARANTINED 처리
            log.warn("재고 캐시 장애 발생, QUARANTINED 분기로 전환 productId={} qty={}", productId, quantity, e);
            return StockDecrementResult.CACHE_DOWN;
        }
    }

    private PaymentEvent quarantineForCacheFailure(PaymentEvent paymentEvent) {
        PaymentEvent quarantined = paymentCommandUseCase.markPaymentAsQuarantined(
                paymentEvent, "재고 캐시 장애로 인한 격리");
        // ADR-13 §2-2b-3: cache 장애 경로 QUARANTINED → 2단계 복구 대기 플래그 set
        quarantined.markQuarantineCompensationPending();
        return quarantined;
    }

    /**
     * TX 원자성 보장 메서드: executePayment(event 전이) + createPendingRecord(outbox 생성)를 단일 TX에서 실행.
     * <p>
     * NOTE: Spring AOP self-invocation 주의 — executePaymentConfirm()에서 직접 호출 시 @Transactional 미적용.
     * paymentCommandUseCase.executePayment()가 @Transactional이므로 그 TX에 createPendingRecord가 참여하는 구조.
     * 완전한 AOP 프록시 경유는 T1-06 이후 리팩토링 대상.
     */
    @Transactional
    public PaymentEvent executePaymentConfirmInTransaction(
            PaymentEvent paymentEvent,
            String paymentKey,
            String orderId
    ) {
        PaymentEvent inProgress = paymentCommandUseCase.executePayment(paymentEvent, paymentKey);
        paymentOutboxUseCase.createPendingRecord(orderId);
        return inProgress;
    }

    private enum StockDecrementResult {
        SUCCESS, REJECTED, CACHE_DOWN
    }

    /**
     * D12 가드: TX 내 outbox/event 재조회 후 조건 충족 시에만 재고 복구 수행.
     * 조건: outbox.status == IN_FLIGHT AND event.status ∈ {READY, IN_PROGRESS, RETRYING}
     * 어느 하나라도 거짓이면 재고 복구를 건너뜀. markPaymentAsFail은 항상 호출되나, fail() no-op으로 이미 종결 시 상태 불변.
     * QUARANTINED는 isCompensatableByFailureHandler()=false 이므로 재고 복구 건너뜀.
     * QUARANTINED 이벤트의 재고 보상은 T1-12 QuarantineCompensationHandler가 전담한다.
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
}
