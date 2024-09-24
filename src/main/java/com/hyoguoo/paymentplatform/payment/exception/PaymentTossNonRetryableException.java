package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossNonRetryableException extends Exception {

    private final String code;
    private final String message;

    private PaymentTossNonRetryableException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentTossNonRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossNonRetryableException(errorCode);
    }
}
