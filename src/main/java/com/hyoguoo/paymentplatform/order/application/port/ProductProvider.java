package com.hyoguoo.paymentplatform.order.application.port;

import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;

public interface ProductProvider {

    ProductInfo getProductInfoById(Long productId);

    void reduceStockWithCommit(Long productId, Integer quantity);

    void increaseStockWithCommit(Long productId, Integer quantity);
}
