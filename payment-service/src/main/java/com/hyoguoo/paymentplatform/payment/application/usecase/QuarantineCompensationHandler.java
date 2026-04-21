package com.hyoguoo.paymentplatform.payment.application.usecase;

/**
 * QUARANTINED 2단계 복구 핸들러.
 * <p>
 * ADR-15(QUARANTINED 보상 주체 = payment-service), §2-2b-3(2단계 분할 설계).
 * <p>
 * 진입점:
 * (a) FCG — pg-service FCG 결과 status=QUARANTINED: 즉시 INCR 금지 (Reconciler 위임)
 * (b) DLQ_CONSUMER — PaymentConfirmDlqConsumer 처리 후 status=QUARANTINED, reasonCode=RETRY_EXHAUSTED:
 * TX 커밋 후 Redis INCR 1회
 * <p>
 * 2단계 복구:
 * 1. TX 내: PaymentEvent QUARANTINED 전이 + payment_history insert + quarantineCompensationPending=true
 * 2. TX 밖: Redis INCR stock 복구 (DLQ_CONSUMER 진입점만). 성공 시 플래그 해제, 실패 시 플래그 유지.
 */
// TODO: T1-12 — 구현 필요. 이 스텁은 RED 컴파일용.
public class QuarantineCompensationHandler {

    public enum QuarantineEntry {
        FCG,
        DLQ_CONSUMER
    }

    public void handle(String orderId, String reason, QuarantineEntry entry) {
        throw new UnsupportedOperationException("미구현");
    }

    public void retryStockRollback(String orderId) {
        throw new UnsupportedOperationException("미구현");
    }
}
