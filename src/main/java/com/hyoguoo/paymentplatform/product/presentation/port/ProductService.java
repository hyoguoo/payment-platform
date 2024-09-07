package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.domain.Product;

public interface ProductService {

    Product getById(Long id);

    Product reduceStockWithCommit(Long productId, Integer reduceStock);

    Product increaseStockWithCommit(Long productId, Integer increaseStock);
}
