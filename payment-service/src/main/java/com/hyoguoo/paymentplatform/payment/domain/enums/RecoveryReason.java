package com.hyoguoo.paymentplatform.payment.domain.enums;

public enum RecoveryReason {
    PG_TERMINAL_FAIL,
    PG_IN_PROGRESS,
    GATEWAY_STATUS_UNKNOWN,
    UNMAPPED,
    CONFIRM_EXHAUSTED,
    /** PG가 DONE을 반환했으나 approvedAt이 null인 상태가 재시도 한도까지 지속된 경우 */
    GUARD_MISSING_APPROVED_AT,
}
