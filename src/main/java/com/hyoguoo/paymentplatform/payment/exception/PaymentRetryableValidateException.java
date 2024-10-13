package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentRetryableValidateException extends Exception {

    private final String code;
    private final String message;

    private PaymentRetryableValidateException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentRetryableValidateException of(PaymentErrorCode errorCode) {
        return new PaymentRetryableValidateException(errorCode);
    }
}
