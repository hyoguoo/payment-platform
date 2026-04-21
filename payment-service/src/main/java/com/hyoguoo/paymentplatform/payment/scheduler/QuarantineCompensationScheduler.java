package com.hyoguoo.paymentplatform.payment.scheduler;

/**
 * QUARANTINED compensation pending 레코드 주기 스캔 스케줄러.
 * <p>
 * QuarantineCompensationHandler의 TX 밖 Redis INCR이 실패하거나 크래시로 플래그가 잔존하는 경우,
 * 이 스케줄러가 주기적으로 스캔하여 retryStockRollback()을 재시도한다.
 */
// TODO: T1-12 — 구현 필요. 이 스텁은 RED 컴파일용.
public class QuarantineCompensationScheduler {

    public void scan() {
        throw new UnsupportedOperationException("미구현");
    }
}
