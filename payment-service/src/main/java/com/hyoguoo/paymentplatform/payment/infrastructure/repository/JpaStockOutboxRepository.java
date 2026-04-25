package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.StockOutboxEntity;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * stock_outbox 테이블 Spring Data JPA 레포지토리.
 * T-J1: stock commit/restore 이벤트 outbox 패턴.
 */
public interface JpaStockOutboxRepository extends JpaRepository<StockOutboxEntity, Long> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockOutboxEntity e SET e.processedAt = :processedAt WHERE e.id = :id")
    void markProcessed(@Param("id") Long id, @Param("processedAt") LocalDateTime processedAt);
}
