package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * user-service HTTP 호출 중 404 응답을 payment 도메인 예외로 표현.
 * UserHttpAdapter 내부에서만 생성하며 port 인터페이스를 오염시키지 않는다.
 */
@Getter
public class UserNotFoundException extends RuntimeException {

    private final String code;

    private UserNotFoundException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static UserNotFoundException of(PaymentErrorCode errorCode) {
        return new UserNotFoundException(errorCode);
    }
}
