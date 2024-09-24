package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public abstract class NonRetryableException extends Exception {

    private final String code;
    private final String message;

    protected NonRetryableException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }
}
