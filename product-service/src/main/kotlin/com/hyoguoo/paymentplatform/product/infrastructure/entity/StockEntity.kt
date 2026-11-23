package com.hyoguoo.paymentplatform.product.infrastructure.entity

import com.hyoguoo.paymentplatform.product.domain.Stock
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * product-service `stock` 테이블 JPA 엔티티.
 * V1__product_schema.sql의 (product_id PK, quantity, updated_at) 스키마와 매핑된다.
 *
 * 기본 키는 상품 번호를 그대로 넣는다(생성 전략 없음). 0 이 아닌 키라 save 는 새 엔티티가 아닌 merge 로 동작한다.
 */
@Entity
@Table(name = "stock")
class StockEntity(
    @Id
    @Column(name = "product_id")
    val productId: Long,
    @Column(name = "quantity", nullable = false)
    val quantity: Int,
) {
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
        protected set

    fun toDomain(): Stock = Stock(productId, quantity)

    companion object {
        fun from(stock: Stock): StockEntity = StockEntity(stock.productId, stock.quantity)
    }
}
