package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QUARANTINED 2단계 복구 핸들러.
 * <p>
 * ADR-15(QUARANTINED 보상 주체 = payment-service), §2-2b-3(2단계 분할 설계).
 * <p>
 * 진입점:
 * (a) FCG — pg-service FCG 결과 status=QUARANTINED: 즉시 INCR 금지 (Reconciler 위임)
 * (b) DLQ_CONSUMER — PaymentConfirmDlqConsumer 처리 후 status=QUARANTINED: TX 커밋 후 Redis INCR 1회
 * <p>
 * 2단계 복구:
 * 1. TX 내: PaymentEvent QUARANTINED 전이 + quarantineCompensationPending=true 플래그 set + 저장.
 *    payment_history insert는 @PaymentStatusChange AOP가 처리.
 * 2. TX 밖 (DLQ_CONSUMER만): Redis INCR stock 복구.
 *    성공 시 플래그 해제(별도 TX), 실패 시 플래그 유지 → QuarantineCompensationScheduler 재시도.
 * <p>
 * 불변식 7b: QUARANTINED 전이 후 Redis INCR 실패 시 플래그는 반드시 유지되어야 한다.
 * FCG 진입점은 Reconciler가 담당하므로 즉시 INCR 금지.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineCompensationHandler {

    public enum QuarantineEntry {
        FCG,
        DLQ_CONSUMER
    }

    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final StockCachePort stockCachePort;
    private final PaymentEventRepository paymentEventRepository;

    /**
     * 진입점 a/b 공통 수렴 핸들러.
     *
     * @param orderId 주문 ID
     * @param reason  격리 사유
     * @param entry   진입점 (FCG 또는 DLQ_CONSUMER)
     */
    public void handle(String orderId, String reason, QuarantineEntry entry) {
        // Step 1: TX 내 — QUARANTINED 전이 + 플래그 set + payment_history insert
        PaymentEvent quarantinedEvent = handleInTransaction(orderId, reason);

        // Step 2: TX 밖 — DLQ_CONSUMER 진입점만 즉시 Redis INCR 시도
        if (entry == QuarantineEntry.DLQ_CONSUMER) {
            attemptStockRollback(orderId, quarantinedEvent);
        }
        // FCG 진입점은 즉시 INCR 금지 — Reconciler(T1-14)가 위임 처리
    }

    /**
     * Scheduler 재시도 진입점 — 플래그 잔존 레코드에 대해 Redis INCR 재시도.
     *
     * @param orderId 주문 ID
     */
    public void retryStockRollback(String orderId) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        attemptStockRollback(orderId, event);
    }

    @Transactional
    public PaymentEvent handleInTransaction(String orderId, String reason) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        // markPaymentAsQuarantined 내부에서 quarantine() 호출 → quarantineCompensationPending=true set됨
        PaymentEvent quarantinedEvent = paymentCommandUseCase.markPaymentAsQuarantined(event, reason);
        // quarantine() 내부에서 이미 플래그 set되지만, Mock 경로 보장을 위해 명시적 set
        quarantinedEvent.markQuarantineCompensationPending();
        return paymentEventRepository.saveOrUpdate(quarantinedEvent);
    }

    private void attemptStockRollback(String orderId, PaymentEvent quarantinedEvent) {
        try {
            rollbackStock(quarantinedEvent);
        } catch (RuntimeException e) {
            // Redis INCR 실패 → 플래그 유지(불변식 7b). QuarantineCompensationScheduler가 재시도.
            log.warn("Redis INCR 실패 — 플래그 유지, Scheduler 재시도 예정 orderId={}", orderId, e);
            return;
        }
        clearPendingFlagInTx(orderId);
    }

    private void rollbackStock(PaymentEvent event) {
        for (PaymentOrder order : event.getPaymentOrderList()) {
            stockCachePort.rollback(order.getProductId(), order.getQuantity());
        }
    }

    @Transactional
    public void clearPendingFlagInTx(String orderId) {
        PaymentEvent event = paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        event.clearQuarantineCompensationPending();
        paymentEventRepository.saveOrUpdate(event);
    }
}
