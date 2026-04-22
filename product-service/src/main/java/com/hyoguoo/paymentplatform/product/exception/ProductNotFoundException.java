package com.hyoguoo.paymentplatform.product.exception;

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import lombok.Getter;

@Getter
public class ProductNotFoundException extends RuntimeException {

    private final ProductErrorCode errorCode;

    private ProductNotFoundException(ProductErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public static ProductNotFoundException of(ProductErrorCode errorCode) {
        return new ProductNotFoundException(errorCode);
    }
}
