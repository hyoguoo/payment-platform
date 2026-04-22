package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA — product 테이블 직접 접근용. 어댑터 외부 노출 금지.
 */
public interface JpaProductRepository extends JpaRepository<ProductEntity, Long> {
}
