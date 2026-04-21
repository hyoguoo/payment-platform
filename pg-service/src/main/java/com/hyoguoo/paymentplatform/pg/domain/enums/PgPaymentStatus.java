package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 PG 결제 상태.
 * payment-service의 PaymentStatus와 동일한 값을 독립 선언 (ADR-30).
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
