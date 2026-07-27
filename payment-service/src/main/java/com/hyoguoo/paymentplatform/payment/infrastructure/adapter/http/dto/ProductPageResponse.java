package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.util.List;

/**
 * product-service GET /api/v1/products (목록 조회) 응답 DTO.
 * product-service presentation.dto.ProductPageResponse 와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 */
public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
