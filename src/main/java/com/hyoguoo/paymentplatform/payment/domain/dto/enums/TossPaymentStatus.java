package com.hyoguoo.paymentplatform.payment.domain.dto.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TossPaymentStatus {

    DONE("DONE", "완료"),
    CANCELED("CANCELED","승인된 결제가 취소된 상태"),
    EXPIRED("EXPIRED", "결제 유효 시간이 만료된 상태"),
    PARTIAL_CANCELED("PARTIAL_CANCELED", "승인된 결제가 부분 취소된 상태"),
    ABORTED("ABORTED", "결제 승인이 실패된 상태"),
    WAITING_FOR_DEPOSIT("WAITING_FOR_DEPOSIT", "입금 대기 중인 상태"),
    IN_PROGRESS("IN_PROGRESS", "결제 수단 정보와 해당 결제수단의 소유자 인증 완료 상태"),
    READY("READY", "결제 생성 초기 상태"),
    ;

    private final String value;
    private final String description;

    public static TossPaymentStatus of(String value) {
        return Arrays.stream(TossPaymentStatus.values())
                .filter(status -> status.getValue().equals(value))
                .findFirst()
                .orElseThrow();
    }
}
