package com.hyoguoo.paymentplatform.product.presentation.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductStockRequest {
    private Long productId;
    private Integer stock;

    public ProductStockRequest(Long productId, Integer stock) {
        this.productId = productId;
        this.stock = stock;
    }
}
