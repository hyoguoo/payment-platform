package com.hyoguoo.paymentplatform.product.presentation;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductPresentationMapper {

    public static ProductInfoResponse toProductInfoResponse(Product product) {
        return ProductInfoResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .stock(product.getStock())
                .build();
    }
}
