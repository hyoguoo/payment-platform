package com.hyoguoo.paymentplatform.payment.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentOutboxStatus {

    PENDING("PENDING"),
    IN_FLIGHT("IN_FLIGHT"),
    DONE("DONE"),
    FAILED("FAILED");

    private final String value;
}
