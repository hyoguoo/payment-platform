package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.application.port.ProductProvider;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.product.presentation.ProductInternalReceiver;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalProductProvider implements ProductProvider {

    private final ProductInternalReceiver productInternalReceiver;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        ProductInfoResponse productInfoResponse = productInternalReceiver.getProductInfoById(productId);

        return PaymentInfrastructureMapper.toProductInfo(productInfoResponse);
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
