package com.hyoguoo.paymentplatform.product.infrastructure.repository

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.hyoguoo.paymentplatform.product.domain.Stock
import com.hyoguoo.paymentplatform.product.infrastructure.entity.StockEntity
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * StockRepository 포트 JPA 어댑터.
 * StockEntity → domain Stock 매핑만 담당한다.
 * 재고 증감 원자성은 호출자(UseCase)가 트랜잭션 + 비관적 락 또는 UPDATE 문으로 보장한다.
 */
@Repository
class StockRepositoryImpl(
    private val jpaStockRepository: JpaStockRepository,
) : StockRepository {
    /**
     * productId 가 null 이면 IllegalArgumentException 으로 실패한다. null 은 HTTP 재고 차감 요청에서만 올 수 있고,
     * 이관 전 Spring Data 의 findById(null) 거부와 같이 500 응답과 트랜잭션 롤백으로 끝난다.
     */
    override fun findByProductId(productId: Long?): Optional<Stock> =
        jpaStockRepository
            .findById(requireNotNull(productId) { "productId 가 비어 있다" })
            .map(StockEntity::toDomain)

    override fun save(stock: Stock): Stock = jpaStockRepository.save(StockEntity.from(stock)).toDomain()
}
