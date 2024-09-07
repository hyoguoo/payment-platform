package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentFoundException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentFoundException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentFoundException of(PaymentErrorCode errorCode) {
        return new PaymentFoundException(errorCode);
    }
}
