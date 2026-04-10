package com.hyoguoo.paymentplatform.payment.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentEventStatus {

    READY("READY"),
    IN_PROGRESS("IN_PROGRESS"),
    RETRYING("RETRYING"),
    DONE("DONE"),
    FAILED("FAILED"),
    CANCELED("CANCELED"),
    PARTIAL_CANCELED("PARTIAL_CANCELED"),
    EXPIRED("EXPIRED"),
    QUARANTINED("QUARANTINED"),
    ;

    private final String value;

    /**
     * 종결 상태 여부를 반환한다.
     * DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED이 terminal.
     * 이 메서드는 LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자다.
     */
    public boolean isTerminal() {
        return switch (this) {
            case DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED -> true;
            case READY, IN_PROGRESS, RETRYING -> false;
        };
    }

    public static PaymentEventStatus of(String value) {
        return Arrays.stream(PaymentEventStatus.values())
                .filter(v -> v.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
