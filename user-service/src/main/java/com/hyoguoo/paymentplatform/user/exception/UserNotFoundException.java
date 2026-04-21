package com.hyoguoo.paymentplatform.user.exception;

import com.hyoguoo.paymentplatform.user.exception.common.UserErrorCode;

public class UserNotFoundException extends RuntimeException {

    private final UserErrorCode errorCode;

    private UserNotFoundException(UserErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public static UserNotFoundException of(UserErrorCode errorCode) {
        return new UserNotFoundException(errorCode);
    }

    public String getCode() {
        return errorCode.getCode();
    }
}
