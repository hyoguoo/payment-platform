package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * StockRepository 포트 JPA 어댑터.
 * StockEntity → domain Stock 매핑만 담당한다.
 * 재고 증감 원자성은 호출자(UseCase)가 트랜잭션 + 비관적 락 또는 UPDATE 문으로 보장한다.
 */
@Repository
@RequiredArgsConstructor
public class StockRepositoryImpl implements StockRepository {

    private final JpaStockRepository jpaStockRepository;

    @Override
    public List<Stock> findAll() {
        return jpaStockRepository
                .findAll()
                .stream()
                .map(StockEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Stock> findByProductId(Long productId) {
        return jpaStockRepository
                .findById(productId)
                .map(StockEntity::toDomain);
    }

    @Override
    public Stock save(Stock stock) {
        return jpaStockRepository.save(StockEntity.from(stock)).toDomain();
    }
}
