package com.hyoguoo.paymentplatform.product.infrastructure.repository

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage
import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository
import com.hyoguoo.paymentplatform.product.domain.Product
import com.hyoguoo.paymentplatform.product.infrastructure.entity.ProductEntity
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/**
 * ProductRepository 포트 JPA 어댑터.
 * product + stock 두 테이블을 읽어 단일 Product 도메인으로 합쳐 반환한다.
 * stock 행이 없으면 quantity=0으로 처리한다 (재고 미할당 상품).
 */
@Repository
class ProductRepositoryImpl(
    private val jpaProductRepository: JpaProductRepository,
    private val jpaStockRepository: JpaStockRepository,
) : ProductRepository {
    override fun findById(id: Long): Product? {
        val product = jpaProductRepository.findByIdOrNull(id) ?: return null
        return product.toDomain(fetchStockQuantity(id))
    }

    override fun findPage(
        page: Int,
        size: Int,
    ): ProductPage {
        val productEntityPage =
            jpaProductRepository.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id")))
        val quantityByProductId = fetchStockQuantities(productEntityPage.content)
        val content =
            productEntityPage.content.map { product ->
                product.toDomain(quantityByProductId[product.id] ?: 0)
            }
        return ProductPage(content, page, size, productEntityPage.totalElements)
    }

    private fun fetchStockQuantity(productId: Long): Int = jpaStockRepository.findByIdOrNull(productId)?.quantity ?: 0

    private fun fetchStockQuantities(products: List<ProductEntity>): Map<Long, Int> =
        jpaStockRepository
            .findAllById(products.mapNotNull { it.id })
            .associate { it.productId to it.quantity }
}
