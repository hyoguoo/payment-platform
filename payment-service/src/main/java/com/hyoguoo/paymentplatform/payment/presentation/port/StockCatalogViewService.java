package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogLookupResult;

public interface StockCatalogViewService {

    /**
     * 상품 확정 재고 목록을 페이지 단위로 조회한다.
     *
     * <p>조회 실패(product-service 장애·타임아웃 등 어떤 런타임 예외든)는 구현체가 흡수하고
     * {@link ProductCatalogLookupResult#unavailable()} 로 반환한다 — 예외를 밖으로 던지지 않는다.
     * 밖으로 던지면 재고 화면 자체가 500 으로 깨진다.
     *
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기
     * @return 조회 결과(성공 / 조회 불가)
     */
    ProductCatalogLookupResult getPage(int page, int size);
}
