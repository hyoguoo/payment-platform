package com.hyoguoo.paymentplatform.paymentgateway.domain.enums;

import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentConfirmResult {
    SUCCESS("SUCCESS"),
    RETRYABLE_FAILURE("RETRYABLE_FAILURE"),
    NON_RETRYABLE_FAILURE("NON_RETRYABLE_FAILURE"),
    ;

    private final String value;

    public static PaymentConfirmResult of(TossPaymentErrorCode tossPaymentErrorCode) {
        if (tossPaymentErrorCode.isRetryableError()) {
            return RETRYABLE_FAILURE;
        } else if (tossPaymentErrorCode.isFailure()) {
            return NON_RETRYABLE_FAILURE;
        } else if (tossPaymentErrorCode.isSuccess()) {
            return SUCCESS;
        }

        throw new IllegalArgumentException();
    }
}
