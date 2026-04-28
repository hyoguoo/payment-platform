package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * user-service HTTP 호출 중 503/429/연결 타임아웃 등 재시도 가능한 오류.
 * UserHttpAdapter 내부에서 throw하며 port 인터페이스를 오염시키지 않는다.
 */
@Getter
public class UserServiceRetryableException extends RuntimeException {

    private final String code;

    private UserServiceRetryableException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static UserServiceRetryableException of(PaymentErrorCode errorCode) {
        return new UserServiceRetryableException(errorCode);
    }
}
