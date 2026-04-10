package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.RecoveryReason;

/**
 * 복구 사이클에서 내릴 결정을 표현하는 순수 도메인 값 객체.
 * Spring 의존 없음.
 */
public record RecoveryDecision(Type type, RecoveryReason reason) {

    public enum Type {
        REJECT_REENTRY,
        COMPLETE_SUCCESS,
        GUARD_MISSING_APPROVED_AT,
        COMPLETE_FAILURE,
        ATTEMPT_CONFIRM,
        RETRY_LATER,
        QUARANTINE,
    }

    // 구현 예정 — RED 스텁
    public static RecoveryDecision from(
            PaymentEvent event,
            PaymentStatusResult result,
            int retryCount,
            int maxRetries
    ) {
        throw new UnsupportedOperationException("미구현");
    }

    // 구현 예정 — RED 스텁
    public static RecoveryDecision fromException(
            PaymentEvent event,
            Exception exception,
            int retryCount,
            int maxRetries
    ) {
        throw new UnsupportedOperationException("미구현");
    }
}
