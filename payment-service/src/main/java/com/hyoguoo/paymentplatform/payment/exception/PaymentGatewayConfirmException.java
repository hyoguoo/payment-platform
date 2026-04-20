package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentGatewayConfirmException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentGatewayConfirmException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentGatewayConfirmException of(PaymentErrorCode errorCode) {
        return new PaymentGatewayConfirmException(errorCode);
    }
}
