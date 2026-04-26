package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

/**
 * product-service POST stock 요청 item.
 */
public record StockCommandItem(Long productId, Integer stock) {}
