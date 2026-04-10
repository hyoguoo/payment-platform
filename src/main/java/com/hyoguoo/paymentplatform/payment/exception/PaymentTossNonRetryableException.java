package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossNonRetryableException extends Exception {

    private final String code;
    private final String message;

    private PaymentTossNonRetryableException(PaymentErrorCode errorCode) {
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
    }

    public static PaymentTossNonRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossNonRetryableException(errorCode);
    }
}
