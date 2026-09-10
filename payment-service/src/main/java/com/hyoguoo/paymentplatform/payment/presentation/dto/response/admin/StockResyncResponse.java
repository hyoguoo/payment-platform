package com.hyoguoo.paymentplatform.payment.presentation.dto.response.admin;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncOverlapStatus;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncResult;

public record StockResyncResponse(Long productId, int quantity, StockResyncOverlapStatus overlapStatus) {

    public static StockResyncResponse from(Long productId, StockResyncResult result) {
        return new StockResyncResponse(productId, result.quantity(), result.overlapStatus());
    }
}
