package com.hyoguoo.paymentplatform.product.infrastructure.entity;

import com.hyoguoo.paymentplatform.product.domain.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * product-service {@code product} 테이블 JPA 엔티티.
 * V1__product_schema.sql의 (id PK, name, price, description, seller_id, created_at, updated_at) 스키마와 매핑된다.
 */
@Getter
@Entity
@Table(name = "product")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Product toDomain(Integer stock) {
        return Product.allArgsBuilder()
                .id(id)
                .name(name)
                .price(price)
                .description(description)
                .stock(stock)
                .sellerId(sellerId)
                .allArgsBuild();
    }
}
