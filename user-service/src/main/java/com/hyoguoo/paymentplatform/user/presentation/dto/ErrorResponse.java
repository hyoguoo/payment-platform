package com.hyoguoo.paymentplatform.user.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 오류 응답 DTO.
 * payment-service 호출자가 status + code + message로 원인 식별.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message);
    }
}
