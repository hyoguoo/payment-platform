package com.hyoguoo.paymentplatform.product.application.port.out

import com.hyoguoo.paymentplatform.product.domain.Stock

/**
 * 재고 outbound 포트.
 */
interface StockRepository {
    /** 재고 행이 없으면 null 을 돌려준다. */
    fun findByProductId(productId: Long): Stock?

    fun save(stock: Stock): Stock
}
