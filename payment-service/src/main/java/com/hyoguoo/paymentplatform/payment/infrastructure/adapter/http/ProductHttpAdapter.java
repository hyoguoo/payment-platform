package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * product-service HTTP 어댑터 (RED stub — 구현 전).
 * ProductPort 구현체.
 */
public class ProductHttpAdapter implements ProductPort {

    private final HttpOperator httpOperator;

    public ProductHttpAdapter(HttpOperator httpOperator) {
        this.httpOperator = httpOperator;
    }

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * product-service GET /api/v1/products/{id} 응답 DTO.
     */
    public record ProductResponse(
            Long id,
            String name,
            BigDecimal price,
            Integer stock,
            Long sellerId
    ) {}
}
