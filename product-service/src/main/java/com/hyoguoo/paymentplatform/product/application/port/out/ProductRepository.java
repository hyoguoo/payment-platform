package com.hyoguoo.paymentplatform.product.application.port.out;

import com.hyoguoo.paymentplatform.product.domain.Product;
import java.util.Optional;

/**
 * 상품 조회 outbound 포트.
 * product + stock 조인 결과를 Product 도메인으로 반환한다.
 */
public interface ProductRepository {

    Optional<Product> findById(Long id);
}
