package com.hyoguoo.paymentplatform.core.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {

    INVALID_INPUT_VALUE("E99001", "적절하지 않은 요청 값입니다."),
    HEADER_NOT_FOUND("E99002", "헤더 값이 존재하지 않습니다."),
    INTERNAL_SERVER_ERROR("E99999", "서버 내부 오류가 발생했습니다.");

    private final String code;
    private final String message;
}
