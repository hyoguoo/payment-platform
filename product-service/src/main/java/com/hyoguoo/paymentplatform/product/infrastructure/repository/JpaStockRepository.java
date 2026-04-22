package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA — stock 테이블 직접 접근용. 어댑터 외부 노출 금지.
 */
public interface JpaStockRepository extends JpaRepository<StockEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.productId = :productId")
    Optional<StockEntity> findByProductIdPessimistic(Long productId);
}
