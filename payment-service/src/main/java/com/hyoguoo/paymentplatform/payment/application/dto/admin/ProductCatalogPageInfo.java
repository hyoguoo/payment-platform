package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 재고 화면(관리자) 상품 목록 페이징 조회 결과 — payment 쪽 도메인 표현.
 * product-service {@code ProductPage} 를 payment 도메인 DTO 로 옮긴 것.
 */
@Getter
@Builder
public class ProductCatalogPageInfo {

    private final List<ProductCatalogEntryInfo> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
}
