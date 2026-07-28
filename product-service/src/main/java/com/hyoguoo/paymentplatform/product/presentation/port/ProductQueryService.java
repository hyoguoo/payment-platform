package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage;
import com.hyoguoo.paymentplatform.product.domain.Product;

/**
 * 상품 조회 inbound 포트.
 * ProductController가 REST 요청을 위임한다.
 */
public interface ProductQueryService {

    Product getById(Long id);

    /**
     * 상품 목록을 확정 재고와 조인해 페이지 단위로 조회한다.
     * 크기 상한·기본값 적용은 호출측(ProductController)의 책임이다.
     *
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기
     * @return 현재 페이지 상품 목록 + 전체 건수
     */
    ProductPage getPage(int page, int size);
}
