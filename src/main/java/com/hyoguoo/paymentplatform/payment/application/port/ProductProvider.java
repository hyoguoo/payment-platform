package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.util.List;

public interface ProductProvider {

    ProductInfo getProductInfoById(Long productId);

    void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList);

    void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList);
}
