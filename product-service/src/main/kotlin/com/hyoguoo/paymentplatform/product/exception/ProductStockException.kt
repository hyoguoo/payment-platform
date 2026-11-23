package com.hyoguoo.paymentplatform.product.exception

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode

class ProductStockException private constructor(
    val errorCode: ProductErrorCode,
) : RuntimeException(errorCode.message) {
    companion object {
        fun of(errorCode: ProductErrorCode): ProductStockException = ProductStockException(errorCode)
    }
}
