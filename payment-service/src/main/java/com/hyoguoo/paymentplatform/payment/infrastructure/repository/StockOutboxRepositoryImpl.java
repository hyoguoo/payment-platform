package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.StockOutboxEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * StockOutboxRepository 포트 JPA 구현체 — stock_outbox 테이블 어댑터.
 */
@Repository
@RequiredArgsConstructor
public class StockOutboxRepositoryImpl implements StockOutboxRepository {

    private final JpaStockOutboxRepository jpaStockOutboxRepository;

    @Override
    public StockOutbox save(StockOutbox stockOutbox) {
        StockOutboxEntity entity = StockOutboxEntity.from(stockOutbox);
        return jpaStockOutboxRepository.save(entity).toDomain();
    }

    @Override
    public Optional<StockOutbox> findById(Long id) {
        return jpaStockOutboxRepository.findById(id)
                .map(StockOutboxEntity::toDomain);
    }

    @Override
    public void markProcessed(Long id, LocalDateTime processedAt) {
        jpaStockOutboxRepository.markProcessed(id, processedAt);
    }
}
