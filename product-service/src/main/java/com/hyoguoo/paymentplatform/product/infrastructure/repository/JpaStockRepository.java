package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA — stock 테이블 직접 접근용. 어댑터 외부 노출 금지.
 */
public interface JpaStockRepository extends JpaRepository<StockEntity, Long> {
}
