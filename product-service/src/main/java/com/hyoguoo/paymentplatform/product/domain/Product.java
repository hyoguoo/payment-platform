package com.hyoguoo.paymentplatform.product.domain;

import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 상품 애그리거트 루트.
 * payment-service의 product 도메인에서 복사 이관 (T3-01).
 * payment-service 원본 삭제는 후속 태스크(T3-06) 범위.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
public class Product {

    private Long id;
    private String name;
    private BigDecimal price;
    private String description;
    private Integer stock;
    private Long sellerId;

    public void decrementStock(int amount) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        if (this.stock < amount) {
            throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK);
        }

        this.stock -= amount;
    }

    public void incrementStock(int amount) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        this.stock += amount;
    }
}
