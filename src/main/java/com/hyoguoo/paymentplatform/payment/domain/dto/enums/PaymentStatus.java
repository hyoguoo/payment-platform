package com.hyoguoo.paymentplatform.payment.domain.dto.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
    READY("READY"),
    IN_PROGRESS("IN_PROGRESS"),
    WAITING_FOR_DEPOSIT("WAITING_FOR_DEPOSIT"),
    DONE("DONE"),
    CANCELED("CANCELED"),
    PARTIAL_CANCELED("PARTIAL_CANCELED"),
    ABORTED("ABORTED"),
    EXPIRED("EXPIRED"),
    UNKNOWN("UNKNOWN"),
    ;

    private final String value;

    public static PaymentStatus of(String value) {
        return Arrays.stream(PaymentStatus.values())
                .filter(status -> status.getValue().equals(value))
                .findFirst()
                .orElse(UNKNOWN);
    }
}
