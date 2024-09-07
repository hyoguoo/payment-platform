package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.service.port.ProductRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final JpaProductRepository jpaProductRepository;

    @Override
    public Optional<Product> findById(Long id) {
        return jpaProductRepository.findById(id);
    }

    @Override
    public Optional<Product> findByIdPessimistic(Long id) {
        return jpaProductRepository.findByIdPessimistic(id);
    }

    @Override
    public Product saveOrUpdate(Product product) {
        return jpaProductRepository.save(product);
    }
}