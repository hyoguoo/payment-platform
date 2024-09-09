package com.hyoguoo.paymentplatform.product.exception;

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import lombok.Getter;

@Getter
public class ProductStockException extends RuntimeException {

    private final String code;
    private final String message;

    private ProductStockException(ProductErrorCode code) {
        this.code = code.getCode();
        this.message = code.getMessage();
    }

    public static ProductStockException of(ProductErrorCode errorCode) {
        return new ProductStockException(errorCode);
    }
}
