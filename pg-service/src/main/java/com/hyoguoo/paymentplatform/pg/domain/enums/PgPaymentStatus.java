package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 PG 결제 상태.
 * payment-service 의 PaymentStatus 와 동일한 값을 두 서비스가 따로 들고 있는다 (공통 jar 금지).
 */
public enum PgPaymentStatus {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED
}
