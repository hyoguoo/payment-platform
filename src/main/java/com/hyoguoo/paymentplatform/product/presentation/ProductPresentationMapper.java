package com.hyoguoo.paymentplatform.product.presentation;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
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
                .sellerId(product.getSellerId())
                .build();
    }

    public static ProductStockCommand toProductStockCommand(ProductStockRequest productStockRequest) {
        return ProductStockCommand.builder()
                .productId(productStockRequest.getProductId())
                .stock(productStockRequest.getStock())
                .build();
    }
}
