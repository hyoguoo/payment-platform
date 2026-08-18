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
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
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
     * @return SUCCESS: 상품 전부 정상 차감 또는 동일 주문·상품 멱등 재진입(ALREADY_DONE) 또는
     *         선차감 기록이 이미 확정인 상품을 건너뜀,
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
     *
     * <p>상품별 반복 진입 전 선차감 기록이 이미 확정인지 먼저 본다 — 확정이면 그 상품은
     * openHold 도 decrementAtomic 도 부르지 않고 건너뛴다. 캐시의 선차감 표시 수명이 다한
     * 뒤에 재요청이 오면 캐시만으로는 이미 팔린 상품에 새 차감이 다시 일어날 수 있어, 표시
     * 수명이 아니라 기록을 보고 판단해야 한다.
     *
     * <p>반복 도중 캐시 예외가 나면 이번 호출이 직접 차감한 상품만 되돌리려 시도한다 —
     * 상품 단위로 쪼갠 뒤로는 예외가 나기 전에 일부만 차감된 상태일 수 있다. 되돌리기 자체가
     * 또 실패해도 그 예외는 삼키지 않고 로그로 남긴 뒤 CACHE_DOWN 을 그대로 반환한다.
     */
    private StockDecrementResult decrementEachProduct(String orderId, List<PaymentOrder> paymentOrderList) {
        List<PaymentOrder> decrementedThisCall = new ArrayList<>();
        try {
            for (PaymentOrder order : paymentOrderList) {
                if (isAlreadyCommitted(orderId, order)) {
                    continue;
                }
                openHoldOrWrapFailure(orderId, order);
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
        } catch (StockHoldRecordWriteFailedException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_HOLD_RECORD_DOWN_QUARANTINE,
                    () -> "orderId=" + orderId + " error=" + e.getCause().getMessage());
            attemptRevertOnCacheFailure(orderId, decrementedThisCall);
            return StockDecrementResult.CACHE_DOWN;
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_CACHE_DOWN_QUARANTINE,
                    () -> "orderId=" + orderId + " error=" + e.getMessage());
            attemptRevertOnCacheFailure(orderId, decrementedThisCall);
            return StockDecrementResult.CACHE_DOWN;
        }
    }

    /**
     * 선차감 기록 저장소 예외를 캐시 예외와 구분되는 타입으로 감싼다 — decrementEachProduct 의
     * catch 분기가 실패 출처(기록 저장소 vs 재고 캐시)를 갈라 서로 다른 이벤트 타입으로 남기기
     * 위함이다. 판정(격리로 전이) 자체는 출처와 무관하게 동일하다.
     */
    private void openHoldOrWrapFailure(String orderId, PaymentOrder order) {
        try {
            stockHoldRecordRepository.openHold(orderId, order);
        } catch (RuntimeException e) {
            throw new StockHoldRecordWriteFailedException(e);
        }
    }

    private static final class StockHoldRecordWriteFailedException extends RuntimeException {

        StockHoldRecordWriteFailedException(Throwable cause) {
            super(cause);
        }
    }

    private boolean isAlreadyCommitted(String orderId, PaymentOrder order) {
        return stockHoldRecordRepository.findSnapshot(orderId, order)
                .map(snapshot -> snapshot.status() == StockHoldRecordStatus.COMMITTED)
                .orElse(false);
    }

    private void rejectDecrementedProducts(String orderId, List<PaymentOrder> decrementedThisCall) {
        for (PaymentOrder order : decrementedThisCall) {
            stockCachePort.rejectCompensate(orderId, order);
        }
    }

    /**
     * 캐시 예외로 반복이 끊겼을 때 이번 호출이 직접 차감한 상품만 되돌린다. 차감이 하나도
     * 성공하지 않았으면 되돌릴 것이 없어 시도하지 않는다. 되돌리기 자체가 다시 실패해도 그
     * 예외를 삼킨 채 조용히 넘어가지 않고 로그로 남긴다 — 어느 쪽이든 선차감 기록은 잡음
     * 상태 그대로 남아 회수 작업의 대상이 된다.
     */
    private void attemptRevertOnCacheFailure(String orderId, List<PaymentOrder> decrementedThisCall) {
        if (decrementedThisCall.isEmpty()) {
            return;
        }
        try {
            rejectDecrementedProducts(orderId, decrementedThisCall);
        } catch (RuntimeException revertException) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_RETENTION_UNRECOVERED,
                    () -> "orderId=" + orderId + " 캐시 장애 되돌리기마저 실패, 기록은 잡음 상태로 회수 대상 error="
                            + revertException.getMessage());
        }
    }

    /**
     * 재고 캐시 장애(CACHE_DOWN) 분기: QUARANTINED 홀딩 전이.
     * QUARANTINED 는 벤더 상태 불명 홀딩 상태다. 캐시 차감은 상품 단위로 쪼개져 일부만 성공한
     * 채 장애가 날 수 있어, 직접 차감에 성공한 상품은 decrementEachProduct 에서 되돌리기를
     * 시도한(실패해도 넘어온) 뒤 이 메서드로 온다 — 이 메서드 자체는 재고 캐시를 다시 건드리지
     * 않고 결제 상태만 QUARANTINED 로 전이한다.
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
