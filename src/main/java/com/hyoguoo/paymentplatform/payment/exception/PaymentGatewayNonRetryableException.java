package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayNonRetryableException extends Exception {

    private final String code;
    private final String message;

    private PaymentGatewayNonRetryableException(PaymentErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public static PaymentGatewayNonRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentGatewayNonRetryableException(errorCode);
    }
}
