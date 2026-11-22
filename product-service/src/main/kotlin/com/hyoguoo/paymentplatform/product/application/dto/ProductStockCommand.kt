package com.hyoguoo.paymentplatform.product.application.dto

@JvmRecord
data class ProductStockCommand(
    val productId: Long?,
    val stock: Int?,
)
