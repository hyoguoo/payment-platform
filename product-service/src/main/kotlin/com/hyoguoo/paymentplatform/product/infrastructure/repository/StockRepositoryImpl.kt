package com.hyoguoo.paymentplatform.product.infrastructure.repository

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.hyoguoo.paymentplatform.product.domain.Stock
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

/**
 * StockRepository 포트 JPA 어댑터.
 * StockEntity → domain Stock 매핑만 담당한다.
 * 재고 증감 원자성은 호출자(UseCase)가 트랜잭션 + 비관적 락 또는 UPDATE 문으로 보장한다.
 */
@Repository
class StockRepositoryImpl(
    private val jpaStockRepository: JpaStockRepository,
) : StockRepository {
    override fun findByProductId(productId: Long): Stock? = jpaStockRepository.findByIdOrNull(productId)?.toDomain()

    override fun save(stock: Stock): Stock = jpaStockRepository.save(StockEntity.from(stock)).toDomain()
}
