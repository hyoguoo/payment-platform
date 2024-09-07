package com.hyoguoo.paymentplatform.product.domain;

import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
public class Product {

    private Long id;
    private String name;
    private BigDecimal price;
    private String description;
    private Integer stock;

    @Builder
    public Product(Long id, String name, BigDecimal price, String description, Integer stock) {
        this.name = name;
        this.price = price;
        this.description = description;
        this.stock = stock;
    }

    public BigDecimal calculateTotalPrice(Integer quantity) {
        return this.price.multiply(BigDecimal.valueOf(quantity));
    }

    public Product decrementStock(int amount) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        if (this.stock < amount) {
            throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK);
        }

        this.stock -= amount;

        return this;
    }

    public Product incrementStock(int amount) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }
        this.stock += amount;

        return this;
    }

    public void validateStockAvailability(int quantity) {
        if (quantity < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK);
        }

        if (this.stock < quantity) {
            throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK);
        }
    }
}

