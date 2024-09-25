package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossConfirmException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentTossConfirmException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentTossConfirmException of(PaymentErrorCode errorCode) {
        return new PaymentTossConfirmException(errorCode);
    }
}
