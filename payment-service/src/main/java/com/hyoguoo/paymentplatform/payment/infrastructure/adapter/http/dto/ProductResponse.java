package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.math.BigDecimal;

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
