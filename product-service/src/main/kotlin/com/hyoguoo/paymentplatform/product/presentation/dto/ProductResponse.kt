package com.hyoguoo.paymentplatform.product.presentation.dto

import com.hyoguoo.paymentplatform.product.domain.Product
import java.math.BigDecimal

@JvmRecord
data class ProductResponse(
    val id: Long,
    val name: String,
    val price: BigDecimal,
    val stock: Int,
    val sellerId: Long,
) {
    companion object {
        @JvmStatic
        fun from(product: Product): ProductResponse =
            ProductResponse(
                id = product.id,
                name = product.name,
                price = product.price,
                stock = product.stock,
                sellerId = product.sellerId,
            )
    }
}
