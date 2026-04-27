package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 PG 승인 결과 상태.
 * payment-service 의 PaymentConfirmResultStatus 와 동일한 값을 두 서비스가 따로 들고 있는다 (공통 jar 금지).
 */
public enum PgConfirmResultStatus {
    SUCCESS,
    RETRYABLE_FAILURE,
    NON_RETRYABLE_FAILURE
}
