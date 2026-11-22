package com.hyoguoo.paymentplatform.product.domain

import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import java.math.BigDecimal

class Product(
    val id: Long,
    val name: String,
    val price: BigDecimal,
    val description: String,
    stock: Int,
    val sellerId: Long,
) {
    var stock: Int = stock
        private set

    fun decrementStock(amount: Int) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK)
        }
        if (stock < amount) {
            throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK)
        }
        stock -= amount
    }

    fun incrementStock(amount: Int) {
        if (amount < 0) {
            throw ProductStockException.of(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK)
        }
        stock += amount
    }
}
