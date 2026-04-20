package com.hyoguoo.paymentplatform.product.exception;

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import lombok.Getter;

@Getter
public class ProductFoundException extends RuntimeException {

    private final String code;
    private final String message;

    private ProductFoundException(ProductErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static ProductFoundException of(ProductErrorCode errorCode) {
        return new ProductFoundException(errorCode);
    }
}
