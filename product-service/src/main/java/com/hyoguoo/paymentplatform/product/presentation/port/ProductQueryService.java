package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.domain.Product;

/**
 * 상품 조회 inbound 포트.
 * ProductController가 REST 요청을 위임한다.
 */
public interface ProductQueryService {

    Product getById(Long id);
}
