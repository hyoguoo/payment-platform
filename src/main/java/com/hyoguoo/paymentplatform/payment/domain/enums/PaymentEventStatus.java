package com.hyoguoo.paymentplatform.payment.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentEventStatus {

    READY("READY"),
    IN_PROGRESS("IN_PROGRESS"),
    DONE("DONE"),
    FAILED("FAILED"),
    CANCELED("CANCELED"),
    PARTIAL_CANCELED("PARTIAL_CANCELED"),
    UNKNOWN("UNKNOWN"),
    ;

    private final String value;

    public static PaymentEventStatus of(String value) {
        return Arrays.stream(PaymentEventStatus.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
