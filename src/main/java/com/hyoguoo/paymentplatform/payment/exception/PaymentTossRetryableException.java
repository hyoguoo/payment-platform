package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossRetryableException extends Exception {

    private final String code;
    private final String message;

    private PaymentTossRetryableException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentTossRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossRetryableException(errorCode);
    }
}
