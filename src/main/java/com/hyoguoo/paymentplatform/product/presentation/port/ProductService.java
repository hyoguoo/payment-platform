package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.domain.Product;

public interface ProductService {

    Product getById(Long id);

    boolean decreaseStockWithCommit(Long productId, Integer reduceStock);

    boolean increaseStockWithCommit(Long productId, Integer increaseStock);
}
