package com.hyoguoo.paymentplatform.product.domain;

import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
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

