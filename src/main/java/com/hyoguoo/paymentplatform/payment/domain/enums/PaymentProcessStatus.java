package com.hyoguoo.paymentplatform.payment.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentProcessStatus {

    PROCESSING("PROCESSING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED");

    private final String value;

    public static PaymentProcessStatus of(String value) {
        return Arrays.stream(PaymentProcessStatus.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
