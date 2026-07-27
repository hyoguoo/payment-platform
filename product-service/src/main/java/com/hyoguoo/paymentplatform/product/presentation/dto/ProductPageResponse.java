package com.hyoguoo.paymentplatform.product.presentation.dto;

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage;
import java.util.List;

/**
 * GET /api/v1/products (목록 조회) 응답 DTO.
 * payment-service 측 재고 목록 조회 어댑터(Task 11)가 이 필드 시그니처에 의존한다.
 */
public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ProductPageResponse from(ProductPage productPage) {
        List<ProductResponse> content = productPage.content().stream()
                .map(ProductResponse::from)
                .toList();
        int totalPages = productPage.size() <= 0
                ? 0
                : (int) Math.ceil((double) productPage.totalElements() / productPage.size());
        return new ProductPageResponse(
                content,
                productPage.page(),
                productPage.size(),
                productPage.totalElements(),
                totalPages
        );
    }
}
