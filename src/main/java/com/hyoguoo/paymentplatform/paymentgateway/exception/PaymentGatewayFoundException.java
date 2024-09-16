package com.hyoguoo.paymentplatform.paymentgateway.exception;

import com.hyoguoo.paymentplatform.paymentgateway.exception.common.PaymentGatewayErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayFoundException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentGatewayFoundException(PaymentGatewayErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentGatewayFoundException of(PaymentGatewayErrorCode errorCode) {
        return new PaymentGatewayFoundException(errorCode);
    }
}
