package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

/**
 * product-service POST stock 요청 item.
 * F-13: ProductHttpAdapter inline record → 독립 패키지 분리.
 */
public record StockCommandItem(Long productId, Integer stock) {}
