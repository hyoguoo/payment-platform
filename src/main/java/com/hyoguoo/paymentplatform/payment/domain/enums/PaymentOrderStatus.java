package com.hyoguoo.paymentplatform.payment.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentOrderStatus {

    READY("READY"),
    CANCELED("CANCELED"),
    DONE("DONE"),
    IN_PROGRESS("IN_PROGRESS"),
    ;

    private final String statusName;

    public static PaymentOrderStatus of(String statusName) {
        return Arrays.stream(PaymentOrderStatus.values())
                .filter(orderStatus -> orderStatus.getStatusName().equals(statusName))
                .findFirst()
                .orElseThrow();
    }
}
