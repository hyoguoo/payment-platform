package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;

public interface ProductProvider {

    ProductInfo getProductInfoById(Long productId);

    boolean decreaseStockWithCommit(Long productId, Integer quantity);

    boolean increaseStockWithCommit(Long productId, Integer quantity);
}
