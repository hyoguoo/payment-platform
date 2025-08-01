package com.hyoguoo.paymentplatform.paymentgateway.exception;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayApiException extends Exception {

    private final String code;
    private final String message;

    public static PaymentGatewayApiException of(String code, String message) {
        return new PaymentGatewayApiException(code, message);
    }
}
