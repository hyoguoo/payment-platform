package com.hyoguoo.paymentplatform.user.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND("E02001", "존재하지 않는 사용자입니다."),
    ;

    private final String code;
    private final String message;
}
