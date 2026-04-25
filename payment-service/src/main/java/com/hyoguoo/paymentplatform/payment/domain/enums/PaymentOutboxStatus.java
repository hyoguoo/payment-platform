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

    /**
     * 종결 상태 여부를 반환한다.
     * DONE, FAILED → true. PaymentEventStatus.isTerminal() 과 대칭되는 SSOT 판별자.
     */
    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }

    /**
     * 워커가 픽업(claim) 가능한 상태인지 반환한다.
     * PENDING → true.
     */
    public boolean isClaimable() {
        return this == PENDING;
    }

    /**
     * IN_FLIGHT 상태인지 반환한다.
     * IN_FLIGHT → true.
     */
    public boolean isInFlight() {
        return this == IN_FLIGHT;
    }
}
