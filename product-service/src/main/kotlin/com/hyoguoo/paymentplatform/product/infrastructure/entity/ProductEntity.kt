package com.hyoguoo.paymentplatform.product.infrastructure.entity

import com.hyoguoo.paymentplatform.product.domain.Product
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * product-service `product` 테이블 JPA 엔티티.
 * V1__product_schema.sql의 (id PK, name, price, description, seller_id, created_at, updated_at) 스키마와 매핑된다.
 *
 * data class 로 두지 않는다. 생성된 equals·hashCode 가 지연 로딩 프록시와 영속 전(id 없음) 상태에서 어긋난다.
 * JPA 가 쓰는 기본 생성자와 open 클래스는 kotlin-jpa·allOpen 플러그인이 만든다(build.gradle.kts).
 */
@Entity
@Table(name = "product")
class ProductEntity(
    @Column(name = "name", length = 200, nullable = false)
    val name: String,
    @Column(name = "price", nullable = false)
    val price: BigDecimal,
    @Column(name = "description", nullable = false)
    val description: String,
    @Column(name = "seller_id", nullable = false)
    val sellerId: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    var createdAt: LocalDateTime? = null
        protected set

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    var updatedAt: LocalDateTime? = null
        protected set

    fun toDomain(stock: Int): Product =
        Product(
            id = checkNotNull(id) { "저장되지 않은 상품 엔티티는 도메인으로 바꿀 수 없다" },
            name = name,
            price = price,
            description = description,
            stock = stock,
            sellerId = sellerId,
        )
}
