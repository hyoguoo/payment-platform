package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.util.List;

/**
 * 상품 조회 outbound port.
 * Phase 2 이후 RemoteProductAdapter(HTTP/gRPC)로 교체 예정.
 */
public interface ProductLookupPort {

    ProductInfo getProductInfoById(Long productId);

    void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList);

    void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList);
}
