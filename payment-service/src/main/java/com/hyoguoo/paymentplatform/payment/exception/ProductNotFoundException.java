package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * product-service HTTP 호출 중 404 응답을 payment 도메인 예외로 표현.
 * ProductHttpAdapter 내부에서만 생성하며 port 인터페이스를 오염시키지 않는다.
 */
@Getter
public class ProductNotFoundException extends RuntimeException {

    private final String code;

    private ProductNotFoundException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static ProductNotFoundException of(PaymentErrorCode errorCode) {
        return new ProductNotFoundException(errorCode);
    }
}
