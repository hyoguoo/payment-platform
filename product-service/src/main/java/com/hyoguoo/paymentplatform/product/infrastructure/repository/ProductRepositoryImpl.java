package com.hyoguoo.paymentplatform.product.infrastructure.repository;

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage;
import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity;
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    @Override
    public ProductPage findPage(int page, int size) {
        Page<ProductEntity> productEntityPage = jpaProductRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))
        );
        Map<Long, Integer> quantityByProductId = fetchStockQuantities(productEntityPage.getContent());
        List<Product> content = productEntityPage.getContent().stream()
                .map(product -> product.toDomain(quantityByProductId.getOrDefault(product.getId(), 0)))
                .toList();

        return new ProductPage(content, page, size, productEntityPage.getTotalElements());
    }

    private Integer fetchStockQuantity(Long productId) {
        return jpaStockRepository.findById(productId)
                .map(StockEntity::getQuantity)
                .orElse(0);
    }

    private Map<Long, Integer> fetchStockQuantities(List<ProductEntity> products) {
        List<Long> productIds = products.stream().map(ProductEntity::getId).toList();
        return jpaStockRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(StockEntity::getProductId, StockEntity::getQuantity, (a, b) -> a));
    }
}
