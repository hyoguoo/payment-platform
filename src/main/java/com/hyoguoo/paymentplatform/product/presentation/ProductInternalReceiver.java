package com.hyoguoo.paymentplatform.product.presentation;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductInternalReceiver {

    private final ProductService productService;

    public ProductInfoResponse getProductInfoById(Long productId) {
        Product product = productService.getById(productId);

        return ProductPresentationMapper.toProductInfoResponse(product);
    }

    public void reduceStockWithCommit(Long productId, Integer quantity) {
        productService.reduceStockWithCommit(productId, quantity);
    }

    public void increaseStockWithCommit(Long productId, Integer quantity) {
        productService.increaseStockWithCommit(productId, quantity);
    }
}
