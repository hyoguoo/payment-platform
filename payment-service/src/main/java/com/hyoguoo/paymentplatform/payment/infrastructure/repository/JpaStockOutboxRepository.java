package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.infrastructure.entity.StockOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * stock_outbox 테이블 Spring Data JPA 레포지토리 — stock commit/restore 이벤트의 outbox 저장소.
 */
public interface JpaStockOutboxRepository extends JpaRepository<StockOutboxEntity, Long> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockOutboxEntity e SET e.processedAt = :processedAt WHERE e.id = :id")
    void markProcessed(@Param("id") Long id, @Param("processedAt") LocalDateTime processedAt);

    @Query("SELECT so FROM StockOutboxEntity so WHERE so.processedAt IS NULL ORDER BY so.id ASC")
    List<StockOutboxEntity> findPendingBatch(Pageable pageable);
}
