package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity;
import com.hyoguoo.paymentplatform.product.application.port.ProductRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final JpaProductRepository jpaProductRepository;

    @Override
    public Optional<Product> findById(Long id) {
        return jpaProductRepository
                .findById(id)
                .map(ProductEntity::toDomain);
    }

    @Override
    public Optional<Product> findByIdPessimistic(Long id) {
        return jpaProductRepository
                .findByIdPessimistic(id)
                .map(ProductEntity::toDomain);
    }

    @Override
    public Product saveOrUpdate(Product product) {
        return jpaProductRepository.save(ProductEntity.from(product)).toDomain();
    }
}
