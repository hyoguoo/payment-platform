package com.hyoguoo.paymentplatform.order.infrastucture.internal;

import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.service.port.ProductProvider;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalProductProvider implements ProductProvider {

    private final ProductService productService;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        return ProductInfo.builder()
                .id(productService.getById(productId).getId())
                .build();
    }

    @Override
    public void reduceStockWithCommit(Long productId, Integer quantity) {
        productService.reduceStockWithCommit(productId, quantity);
    }

    @Override
    public void increaseStockWithCommit(Long productId, Integer quantity) {
        productService.increaseStockWithCommit(productId, quantity);
    }
}
