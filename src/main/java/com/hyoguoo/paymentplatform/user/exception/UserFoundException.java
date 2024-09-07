package com.hyoguoo.paymentplatform.user.exception;

import com.hyoguoo.paymentplatform.user.exception.common.UserErrorCode;
import lombok.Getter;

@Getter
public class UserFoundException extends RuntimeException {

    private final String code;
    private final String message;

    private UserFoundException(UserErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static UserFoundException of(UserErrorCode errorCode) {
        return new UserFoundException(errorCode);
    }
}
