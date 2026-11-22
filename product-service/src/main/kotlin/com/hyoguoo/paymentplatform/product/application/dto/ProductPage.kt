package com.hyoguoo.paymentplatform.product.application.dto

import com.hyoguoo.paymentplatform.product.domain.Product

@JvmRecord
data class ProductPage(
    val content: List<Product>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
)
