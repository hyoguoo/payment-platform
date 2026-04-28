package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentValidException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentValidException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentValidException of(PaymentErrorCode errorCode) {
        return new PaymentValidException(errorCode);
    }
}
