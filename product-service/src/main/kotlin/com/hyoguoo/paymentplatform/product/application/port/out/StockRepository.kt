package com.hyoguoo.paymentplatform.product.application.port.out

import com.hyoguoo.paymentplatform.product.domain.Stock
import java.util.Optional

/**
 * 재고 outbound 포트.
 */
interface StockRepository {
    fun findByProductId(productId: Long?): Optional<Stock>

    fun save(stock: Stock): Stock
}
