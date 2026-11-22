package com.hyoguoo.paymentplatform.product.application.usecase

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.hyoguoo.paymentplatform.product.domain.Stock
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 동기 재고 증감 유스케이스.
 *
 * HTTP POST /api/v1/products/stock/{decrease,increase} 요청을 처리한다.
 * 비동기 StockCommitUseCase(이벤트 dedupe 포함)와 달리 단순 CRUD 경로다.
 *
 * 불변식:
 * - decrease 시 음수 stock 방어 — NOT_ENOUGH_STOCK throw
 * - 존재하지 않는 productId — PRODUCT_NOT_FOUND throw (부분 실패 시 트랜잭션 롤백)
 */
@Service
class StockCommandUseCase(
    private val stockRepository: StockRepository,
) : StockCommandService {
    @Transactional
    override fun decreaseForOrders(commands: List<ProductStockCommand>) {
        for (command in commands) {
            val current = loadStock(command.productId)
            // 요청 항목의 stock 이 비어 있으면 NPE 로 실패해 트랜잭션 전체를 롤백한다(자바의 언박싱 NPE 와 같은 결과).
            val newQuantity = current.quantity - command.stock!!
            if (newQuantity < 0) {
                throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK)
            }
            stockRepository.save(Stock(current.productId, newQuantity))
        }
    }

    private fun loadStock(productId: Long?): Stock =
        stockRepository
            .findByProductId(productId)
            .orElseThrow { ProductNotFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND) }
}
