package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentStatusException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentStatusException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentStatusException of(PaymentErrorCode errorCode) {
        return new PaymentStatusException(errorCode);
    }
}
