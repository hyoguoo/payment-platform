package com.hyoguoo.paymentplatform.payment.domain.dto.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentConfirmResultStatus {
    SUCCESS("SUCCESS"),
    RETRYABLE_FAILURE("RETRYABLE_FAILURE"),
    NON_RETRYABLE_FAILURE("NON_RETRYABLE_FAILURE"),
    ;

    private final String value;

    public static PaymentConfirmResultStatus of(String value) {
        return Arrays.stream(PaymentConfirmResultStatus.values())
                .filter(paymentConfirmResultStatus -> paymentConfirmResultStatus.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
