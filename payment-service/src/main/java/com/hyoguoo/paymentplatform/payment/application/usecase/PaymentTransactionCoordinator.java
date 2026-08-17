package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.aspect.annotation.PaymentStatusChangeTrigger;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final StockCachePort stockCachePort;
    private final StockHoldRecordRepository stockHoldRecordRepository;
    private final PaymentConfirmPublisherPort confirmPublisher;

    /**
     * 재고 캐시 원자 DECR — 주문 단위 선점을 잡은 뒤 상품별로 (기록 → 차감) 을 반복한다.
     * TX 외부에서 실행된다(호출자 OutboxAsyncConfirmService도 TX 없음).
     *
     * @param orderId          결제 주문 ID (선점·dedup token key 에 사용)
     * @param paymentOrderList 차감 대상 상품 목록
     * @return SUCCESS: 상품 전부 정상 차감 또는 동일 주문·상품 멱등 재진입(ALREADY_DONE),
     *         REJECTED: 상품 중 하나라도 재고 부족(이번 호출이 직접 차감한 상품만 되돌린 뒤 거절),
     *         CACHE_DOWN: Redis 호출 예외(되돌리기 호출 포함),
     *         ALREADY_PROCESSING: 동일 주문을 다른 요청이 이미 처리 중이라 선점을 잡지 못함
     */
    public StockDecrementResult decrementStock(String orderId, List<PaymentOrder> paymentOrderList) {
        Optional<String> lockToken = stockCachePort.acquireOrderLock(orderId);
        if (lockToken.isEmpty()) {
            return StockDecrementResult.ALREADY_PROCESSING;
        }
        try {
            return decrementEachProduct(orderId, paymentOrderList);
        } finally {
            stockCachePort.releaseOrderLock(orderId, lockToken.get());
        }
    }

    /**
     * 상품마다 선차감 기록을 먼저 적고 차감한다 — 기록이 차감보다 먼저 남아야 차감 직전에
     * 죽어도 그 상품이 회수 대상으로 잡힌다. 부족을 만나면 이번 호출에서 직접 차감에 성공한
     * 상품만 거절 전용 되돌리기로 되돌린다 — 이미 처리됨(ALREADY_DONE)을 받은 상품은 이번
     * 호출이 차감한 것이 아니므로 대상에서 뺀다. 되돌리기 호출도 같은 예외 우산 아래 둬,
     * 부족 판정 뒤 되돌리기 자체가 인프라 장애로 실패하는 조합도 CACHE_DOWN 으로 흡수한다.
     */
    private StockDecrementResult decrementEachProduct(String orderId, List<PaymentOrder> paymentOrderList) {
        List<PaymentOrder> decrementedThisCall = new ArrayList<>();
        try {
            for (PaymentOrder order : paymentOrderList) {
                stockHoldRecordRepository.openHold(orderId, order);
                StockDecrementAtomicResult result = stockCachePort.decrementAtomic(orderId, order);
                if (result == StockDecrementAtomicResult.INSUFFICIENT) {
                    rejectDecrementedProducts(orderId, decrementedThisCall);
                    return StockDecrementResult.REJECTED;
                }
                if (result == StockDecrementAtomicResult.OK) {
                    decrementedThisCall.add(order);
                }
            }
            return StockDecrementResult.SUCCESS;
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_CACHE_DOWN_QUARANTINE,
                    () -> "orderId=" + orderId + " error=" + e.getMessage());
            return StockDecrementResult.CACHE_DOWN;
        }
    }

    private void rejectDecrementedProducts(String orderId, List<PaymentOrder> decrementedThisCall) {
        for (PaymentOrder order : decrementedThisCall) {
            stockCachePort.rejectCompensate(orderId, order);
        }
    }

    /**
     * 재고 캐시 장애(CACHE_DOWN) 분기: QUARANTINED 홀딩 전이.
     * QUARANTINED 는 벤더 상태 불명 홀딩 상태로, 캐시 차감이 일어나지 않았으므로 재고 복구도 수행하지 않는다.
     */
    @Transactional
    public PaymentEvent markStockCacheDownQuarantine(PaymentEvent paymentEvent) {
        return paymentCommandUseCase.markPaymentAsQuarantined(
                paymentEvent, "재고 캐시 장애로 인한 격리", PaymentStatusChangeTrigger.STOCK_CACHE_DOWN);
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

    public enum StockDecrementResult {
        SUCCESS, REJECTED, CACHE_DOWN, ALREADY_PROCESSING
    }
}
