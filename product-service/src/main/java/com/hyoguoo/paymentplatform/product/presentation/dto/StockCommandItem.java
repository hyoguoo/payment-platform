package com.hyoguoo.paymentplatform.product.presentation.dto;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;

/**
 * POST /api/v1/products/stock/{decrease,increase} 요청 item.
 * payment-service ProductHttpAdapter.StockCommandItem과 필드 시그니처가 일치해야 한다.
 */
public record StockCommandItem(Long productId, Integer stock) {

    public ProductStockCommand toCommand() {
        return new ProductStockCommand(productId, stock);
    }
}
