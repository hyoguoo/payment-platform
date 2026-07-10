package com.hyoguoo.paymentplatform.payment.presentation.port;

public interface PaymentRecoveryAdminService {

    /**
     * 격리(QUARANTINED) 결제를 안전 종결(FAILED)로 복구한다.
     *
     * @param orderId 대상 주문 ID
     * @param reason  안전 종결 사유(필수)
     */
    void resolveQuarantine(String orderId, String reason);

    /**
     * {@code events.confirmed.dlq} 에 적재된 유실 메시지를 원 토픽으로 재주입한다.
     *
     * @param orderId 재주입 대상 주문 ID
     */
    void reprocessDlq(String orderId);
}
