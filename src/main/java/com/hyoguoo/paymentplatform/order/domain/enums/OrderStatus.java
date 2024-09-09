package com.hyoguoo.paymentplatform.order.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OrderStatus {

    READY("READY"),
    CANCELED("CANCELED"),
    DONE("DONE"),
    IN_PROGRESS("IN_PROGRESS"),
    ;

    private final String statusName;

    public static OrderStatus of(String statusName) {
        return Arrays.stream(OrderStatus.values())
                .filter(orderStatus -> orderStatus.getStatusName().equals(statusName))
                .findFirst()
                .orElseThrow();
    }
}
