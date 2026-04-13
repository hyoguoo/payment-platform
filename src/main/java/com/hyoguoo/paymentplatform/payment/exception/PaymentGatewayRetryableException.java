package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayRetryableException extends Exception {

    private final String code;
    private final String message;

    private PaymentGatewayRetryableException(PaymentErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public static PaymentGatewayRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentGatewayRetryableException(errorCode);
    }
}
