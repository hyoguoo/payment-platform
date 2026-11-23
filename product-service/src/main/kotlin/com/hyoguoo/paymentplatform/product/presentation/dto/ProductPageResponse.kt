package com.hyoguoo.paymentplatform.product.presentation.dto

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage
import kotlin.math.ceil

data class ProductPageResponse(
    val content: List<ProductResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
) {
    companion object {
        fun from(productPage: ProductPage): ProductPageResponse =
            ProductPageResponse(
                content = productPage.content.map(ProductResponse::from),
                page = productPage.page,
                size = productPage.size,
                totalElements = productPage.totalElements,
                totalPages = totalPages(productPage.totalElements, productPage.size),
            )

        private fun totalPages(
            totalElements: Long,
            size: Int,
        ): Int = if (size <= 0) 0 else ceil(totalElements.toDouble() / size).toInt()
    }
}
