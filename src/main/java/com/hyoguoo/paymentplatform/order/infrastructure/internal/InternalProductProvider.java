package com.hyoguoo.paymentplatform.order.infrastructure.internal;

import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.order.infrastructure.OrderInfrastructureMapper;
import com.hyoguoo.paymentplatform.product.presentation.ProductInternalReceiver;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoClientResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalProductProvider implements ProductProvider {

    private final ProductInternalReceiver productInternalReceiver;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        ProductInfoClientResponse productInfoClientResponse = productInternalReceiver.getProductInfoById(productId);

        return OrderInfrastructureMapper.toProductInfo(productInfoClientResponse);
    }

    @Override
    public void reduceStockWithCommit(Long productId, Integer quantity) {
        productInternalReceiver.reduceStockWithCommit(productId, quantity);
    }

    @Override
    public void increaseStockWithCommit(Long productId, Integer quantity) {
        productInternalReceiver.increaseStockWithCommit(productId, quantity);
    }
}
