package com.hyoguoo.paymentplatform.product.application.port.out;

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage;
import com.hyoguoo.paymentplatform.product.domain.Product;
import java.util.Optional;

/**
 * 상품 조회 outbound 포트.
 * product + stock 조인 결과를 Product 도메인으로 반환한다.
 */
public interface ProductRepository {

    Optional<Product> findById(Long id);

    /**
     * 상품 목록을 확정 재고와 조인해 페이지 단위로 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     * @param size 페이지 크기
     * @return 현재 페이지 상품 목록 + 전체 건수
     */
    ProductPage findPage(int page, int size);
}
