package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.domain.Product;
import java.util.List;

public interface ProductService {

    Product getById(Long id);

    void decreaseStockForOrders(List<ProductStockCommand> productStockCommandList);

    void increaseStockForOrders(List<ProductStockCommand> productStockCommandList);
}
