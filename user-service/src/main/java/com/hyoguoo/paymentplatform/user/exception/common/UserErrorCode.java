package com.hyoguoo.paymentplatform.user.exception.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode {

    USER_NOT_FOUND("USER_001", "사용자를 찾을 수 없습니다.");

    private final String code;
    private final String message;
}
