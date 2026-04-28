package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Toss Payments API 의 status 문자열을 {@link PgPaymentStatus} 로 매핑한다.
 * pg-service 내부 벤더 상태 해석 — payment-service 에는 노출하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum TossPaymentStatus {

    READY("READY", PgPaymentStatus.READY),
    IN_PROGRESS("IN_PROGRESS", PgPaymentStatus.IN_PROGRESS),
    WAITING_FOR_DEPOSIT("WAITING_FOR_DEPOSIT", PgPaymentStatus.WAITING_FOR_DEPOSIT),
    DONE("DONE", PgPaymentStatus.DONE),
    CANCELED("CANCELED", PgPaymentStatus.CANCELED),
    PARTIAL_CANCELED("PARTIAL_CANCELED", PgPaymentStatus.PARTIAL_CANCELED),
    ABORTED("ABORTED", PgPaymentStatus.ABORTED),
    EXPIRED("EXPIRED", PgPaymentStatus.EXPIRED);

    private final String value;
    private final PgPaymentStatus pgStatus;

    public static Optional<TossPaymentStatus> of(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst();
    }
}
