package com.hyoguoo.paymentplatform.product.application.dto

data class ProductStockCommand(
    val productId: Long?,
    val stock: Int?,
)
