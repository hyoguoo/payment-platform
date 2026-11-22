package com.hyoguoo.paymentplatform.product.mock

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.hyoguoo.paymentplatform.product.domain.Stock
import java.util.concurrent.ConcurrentHashMap

/**
 * StockRepository Fake — in-memory 구현체 (product-service 테스트 전용).
 *
 * Thread-safe: ConcurrentHashMap.compute 로 원자적 increment/decrement 보장.
 * StockCommitUseCase 단위 테스트에서 소비한다.
 */
class FakeStockRepository : StockRepository {
    private val store = ConcurrentHashMap<Long, Int>()

    override fun findByProductId(productId: Long): Stock? {
        val quantity = store[productId] ?: return null
        return Stock(productId, quantity)
    }

    override fun save(stock: Stock): Stock {
        store[stock.productId] = stock.quantity
        return stock
    }

    // --- 원자적 재고 변경 헬퍼 ---

    /**
     * 재고를 원자적으로 증가시킨다.
     *
     * @param productId 상품 식별자
     * @param delta 증가량 (양수)
     * @return 변경 후 재고
     */
    fun increment(
        productId: Long,
        delta: Int,
    ): Int = store.compute(productId) { _, current -> (current ?: 0) + delta }!!

    /**
     * 재고를 원자적으로 감소시킨다. 음수가 되면 감소하지 않고 false 반환.
     *
     * @param productId 상품 식별자
     * @param delta 감소량 (양수)
     * @return 감소 성공 여부
     */
    fun decrement(
        productId: Long,
        delta: Int,
    ): Boolean {
        var success = false
        store.compute(productId) { _, current ->
            val cur = current ?: 0
            val after = cur - delta
            if (after < 0) {
                success = false
                cur
            } else {
                success = true
                after
            }
        }
        return success
    }

    // --- 검증 헬퍼 ---

    fun size(): Int = store.size

    fun clear() {
        store.clear()
    }
}
