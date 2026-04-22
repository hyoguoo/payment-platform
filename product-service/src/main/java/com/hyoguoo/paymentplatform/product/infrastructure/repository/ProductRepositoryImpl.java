package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * ProductRepository 포트 JPA 어댑터.
 * product + stock 두 테이블을 읽어 단일 Product 도메인으로 합쳐 반환한다.
 * stock 행이 없으면 quantity=0으로 처리한다 (재고 미할당 상품).
 */
@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final JpaProductRepository jpaProductRepository;
    private final JpaStockRepository jpaStockRepository;

    @Override
    public Optional<Product> findById(Long id) {
        return jpaProductRepository.findById(id)
                .map(product -> product.toDomain(fetchStockQuantity(id)));
    }

    private Integer fetchStockQuantity(Long productId) {
        return jpaStockRepository.findById(productId)
                .map(StockEntity::getQuantity)
                .orElse(0);
    }
}
