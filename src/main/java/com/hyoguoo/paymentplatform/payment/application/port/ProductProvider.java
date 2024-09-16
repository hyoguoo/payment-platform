package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;

public interface ProductProvider {

    ProductInfo getProductInfoById(Long productId);

    void reduceStockWithCommit(Long productId, Integer quantity);

    void increaseStockWithCommit(Long productId, Integer quantity);
}
