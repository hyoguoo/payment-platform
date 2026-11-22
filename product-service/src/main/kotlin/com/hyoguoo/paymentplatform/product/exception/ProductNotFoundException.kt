package com.hyoguoo.paymentplatform.product.exception

import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode

class ProductNotFoundException private constructor(
    val errorCode: ProductErrorCode,
) : RuntimeException(errorCode.message) {
    companion object {
        fun of(errorCode: ProductErrorCode): ProductNotFoundException = ProductNotFoundException(errorCode)
    }
}
