package com.hyoguoo.paymentplatform.product.presentation;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import java.util.List;
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

    public void decreaseStockForOrders(List<ProductStockRequest> productStockRequestList) {
        List<ProductStockCommand> productStockCommandList = productStockRequestList.stream()
                .map(ProductPresentationMapper::toProductStockCommand)
                .toList();

        productService.decreaseStockForOrders(productStockCommandList);
    }

    public void increaseStockForOrders(List<ProductStockRequest> productStockRequestList) {
        List<ProductStockCommand> productStockCommandList = productStockRequestList.stream()
                .map(ProductPresentationMapper::toProductStockCommand)
                .toList();

        productService.increaseStockForOrders(productStockCommandList);
    }
}
