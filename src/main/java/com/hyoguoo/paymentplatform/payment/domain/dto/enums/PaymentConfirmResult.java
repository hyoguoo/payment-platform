package com.hyoguoo.paymentplatform.payment.domain.dto.enums;

import java.util.Arrays;
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

    public static PaymentConfirmResult of(String value) {
        return Arrays.stream(PaymentConfirmResult.values())
                .filter(paymentConfirmResult -> paymentConfirmResult.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
