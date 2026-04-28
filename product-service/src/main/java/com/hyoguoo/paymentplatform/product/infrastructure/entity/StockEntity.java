package com.hyoguoo.paymentplatform.product.infrastructure.entity;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * product-service {@code stock} 테이블 JPA 엔티티.
 * V1__product_schema.sql의 (product_id PK, quantity, updated_at) 스키마와 매핑된다.
 */
@Getter
@Entity
@Table(name = "stock")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public static StockEntity from(Stock stock) {
        return StockEntity.builder()
                .productId(stock.getProductId())
                .quantity(stock.getQuantity())
                .build();
    }

    public Stock toDomain() {
        return Stock.allArgsBuilder()
                .productId(productId)
                .quantity(quantity)
                .allArgsBuild();
    }
}
