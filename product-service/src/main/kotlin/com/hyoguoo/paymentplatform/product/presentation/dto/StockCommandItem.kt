package com.hyoguoo.paymentplatform.product.presentation.dto

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand

/**
 * 재고 차감 요청 항목. 필드가 비어 와도 역직렬화는 성공하고 처리 단계에서 실패한다(지금 동작 유지).
 */
@JvmRecord
data class StockCommandItem(
    val productId: Long?,
    val stock: Int?,
) {
    fun toCommand(): ProductStockCommand = ProductStockCommand(productId, stock)
}
