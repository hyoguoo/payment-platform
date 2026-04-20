package com.hyoguoo.paymentplatform.paymentgateway.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentGatewayErrorCode implements ErrorCode {

    TOSS_PAYMENT_INFO_NOT_FOUND("E02001", "존재하지 않는 토스 결제 정보입니다."),
    ;

    private final String code;
    private final String message;
}
