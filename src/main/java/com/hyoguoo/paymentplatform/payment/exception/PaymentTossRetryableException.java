package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

@Getter
public class PaymentTossRetryableException extends RetryableException {

    private PaymentTossRetryableException(PaymentErrorCode code) {
        super(code);
    }

    public static PaymentTossRetryableException of(PaymentErrorCode errorCode) {
        return new PaymentTossRetryableException(errorCode);
    }
}
