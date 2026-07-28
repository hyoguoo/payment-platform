package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import lombok.Builder;
import lombok.Getter;

/**
 * 재고 화면(관리자) 상품 목록 조회 결과 — 조회 성공과 조회 불가(product-service 장애·타임아웃
 * 등 어떤 런타임 예외든)를 구분해 표현한다.
 *
 * <p>조회 실패 흡수와 폴백 판단은 이 결과를 만드는 {@code StockCatalogViewServiceImpl}
 * (application 계층, presentation 입력 포트 {@code StockCatalogViewService} 구현)의
 * 책임이다 — presentation 계층({@code StockViewController})은 이 결과를 모델에 담는
 * 일만 한다.
 */
@Getter
@Builder
public class ProductCatalogLookupResult {

    /** 조회 불가({@code unavailable=true})면 null. */
    private final ProductCatalogPageInfo page;
    private final boolean unavailable;

    public static ProductCatalogLookupResult available(ProductCatalogPageInfo page) {
        return ProductCatalogLookupResult.builder()
                .page(page)
                .unavailable(false)
                .build();
    }

    public static ProductCatalogLookupResult unavailable() {
        return ProductCatalogLookupResult.builder()
                .page(null)
                .unavailable(true)
                .build();
    }
}
