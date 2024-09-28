package com.hyoguoo.paymentplatform.paymentgateway.exception;

import com.hyoguoo.paymentplatform.paymentgateway.exception.common.PaymentGatewayErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayApiException extends Exception {

    private final String code;
    private final String message;

    private PaymentGatewayApiException(PaymentGatewayErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    private PaymentGatewayApiException(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public static PaymentGatewayApiException of(PaymentGatewayErrorCode errorCode) {
        return new PaymentGatewayApiException(errorCode);
    }

    public static PaymentGatewayApiException of(String code, String message) {
        return new PaymentGatewayApiException(code, message);
    }
}
