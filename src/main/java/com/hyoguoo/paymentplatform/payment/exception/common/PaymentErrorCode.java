package com.hyoguoo.paymentplatform.payment.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    ORDER_NOT_FOUND("E03001", "존재하지 않는 주문입니다."),
    INVALID_TOTAL_AMOUNT("E03002", "유효하지 않은 총 주문 금액입니다."),
    INVALID_ORDER_ID("E03003", "유효하지 않은 주문 ID입니다."),
    INVALID_USER_ID("E03004", "유효하지 않은 사용자 ID입니다."),
    INVALID_PAYMENT_KEY("E03005", "유효하지 않은 결제 키입니다."),
    NOT_CANCELED_PAYMENT("E03006", "취소되지 않은 결제입니다."),
    NOT_DONE_PAYMENT("E03007", "완료되지 않은 결제입니다."),
    NOT_IN_PROGRESS_ORDER("E03008", "진행 중인 주문이 아닙니다.")
    ;

    private final String code;
    private final String message;
}
