package com.hyoguoo.paymentplatform.payment.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentOrderStatus {

    NOT_STARTED("NOT_STARTED"),
    EXECUTING("EXECUTING"),
    SUCCESS("SUCCESS"),
    FAIL("FAIL"),
    CANCEL("CANCEL"),
    EXPIRED("EXPIRED"),
    UNKNOWN("UNKNOWN"),
    ;

    private final String value;

    public static PaymentOrderStatus of(String value) {
        return Arrays.stream(PaymentOrderStatus.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
