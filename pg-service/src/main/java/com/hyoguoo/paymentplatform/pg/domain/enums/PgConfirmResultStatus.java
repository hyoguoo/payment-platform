package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 PG 승인 결과 상태.
 * payment-service의 PaymentConfirmResultStatus와 동일한 값을 독립 선언 (ADR-30).
 */
public enum PgConfirmResultStatus {
    SUCCESS,
    RETRYABLE_FAILURE,
    NON_RETRYABLE_FAILURE
}
