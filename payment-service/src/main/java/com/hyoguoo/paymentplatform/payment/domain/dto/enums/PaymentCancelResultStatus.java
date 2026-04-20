package com.hyoguoo.paymentplatform.payment.domain.dto.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentCancelResultStatus {
    SUCCESS("SUCCESS"),
    FAILURE("FAILURE"),
    ;

    private final String value;

    public static PaymentCancelResultStatus of(String value) {
        return Arrays.stream(PaymentCancelResultStatus.values())
                .filter(status -> status.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
