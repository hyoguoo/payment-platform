package com.hyoguoo.paymentplatform.payment.domain.enums;

public enum RecoveryReason {
    PG_TERMINAL_FAIL,
    PG_NOT_FOUND,
    PG_IN_PROGRESS,
    GATEWAY_STATUS_UNKNOWN,
    UNMAPPED,
    CONFIRM_RETRYABLE_FAILURE,
    CONFIRM_EXHAUSTED,
}
