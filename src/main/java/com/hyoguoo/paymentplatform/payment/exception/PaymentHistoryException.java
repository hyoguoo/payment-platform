package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentHistoryException extends RuntimeException {

    private final String code;
    private final String message;

    private PaymentHistoryException(PaymentErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static PaymentHistoryException of(PaymentErrorCode errorCode) {
        return new PaymentHistoryException(errorCode);
    }
}
