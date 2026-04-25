package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.math.BigDecimal;

/**
 * product-service GET /api/v1/products/{id} 응답 DTO.
 * F-13: ProductHttpAdapter inline record → 독립 패키지 분리.
 */
public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Integer stock,
        Long sellerId
) {}
