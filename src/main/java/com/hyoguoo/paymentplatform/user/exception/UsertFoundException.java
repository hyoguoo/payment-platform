package com.hyoguoo.paymentplatform.user.exception;

import com.hyoguoo.paymentplatform.user.exception.common.UserErrorCode;
import lombok.Getter;

@Getter
public class UsertFoundException extends RuntimeException {

    private final String code;
    private final String message;

    private UsertFoundException(UserErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static UsertFoundException of(UserErrorCode errorCode) {
        return new UsertFoundException(errorCode);
    }
}
