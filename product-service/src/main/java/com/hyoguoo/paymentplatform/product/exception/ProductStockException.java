package com.hyoguoo.paymentplatform.product.exception;

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import lombok.Getter;

@Getter
public class ProductStockException extends RuntimeException {

    private final ProductErrorCode errorCode;

    private ProductStockException(ProductErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public static ProductStockException of(ProductErrorCode errorCode) {
        return new ProductStockException(errorCode);
    }
}
