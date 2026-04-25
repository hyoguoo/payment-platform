package com.hyoguoo.paymentplatform.product.presentation.dto;

import com.hyoguoo.paymentplatform.product.domain.Product;
import java.math.BigDecimal;

/**
 * GET /api/v1/products/{id} 응답 DTO.
 * payment-service infrastructure.adapter.http.dto.ProductResponse와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 */
public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Integer stock,
        Long sellerId
) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStock(),
                product.getSellerId()
        );
    }
}
