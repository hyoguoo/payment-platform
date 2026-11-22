package com.hyoguoo.paymentplatform.product.presentation

import com.hyoguoo.paymentplatform.product.presentation.dto.ProductPageResponse
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductResponse
import com.hyoguoo.paymentplatform.product.presentation.dto.StockCommandItem
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 상품 REST 컨트롤러.
 * payment-service ProductHttpAdapter(@ConditionalOnProperty product.adapter.type=http)와 페어.
 *
 * - GET /api/v1/products — 상품 목록 페이징 조회 (관리자 화면용)
 * - GET /api/v1/products/{id} — 상품+재고 조회
 * - POST /api/v1/products/stock/decrease — 동기 재고 차감
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val productQueryService: ProductQueryService,
    private val stockCommandService: StockCommandService,
) {
    @GetMapping
    fun getProducts(
        @RequestParam(defaultValue = DEFAULT_PAGE_PARAM) page: Int,
        @RequestParam(defaultValue = DEFAULT_SIZE_PARAM) size: Int,
    ): ResponseEntity<ProductPageResponse> {
        val boundedPage = page.coerceAtLeast(MIN_PAGE)
        val boundedSize = size.coerceIn(MIN_SIZE, MAX_SIZE)
        return ResponseEntity.ok(ProductPageResponse.from(productQueryService.getPage(boundedPage, boundedSize)))
    }

    @GetMapping("/{id}")
    fun getProduct(
        @PathVariable id: Long,
    ): ResponseEntity<ProductResponse> = ResponseEntity.ok(ProductResponse.from(productQueryService.getById(id)))

    @PostMapping("/stock/decrease")
    fun decreaseStock(
        @RequestBody items: List<StockCommandItem>,
    ): ResponseEntity<Void> {
        stockCommandService.decreaseForOrders(items.map(StockCommandItem::toCommand))
        return ResponseEntity.ok().build()
    }

    private companion object {
        const val DEFAULT_PAGE_PARAM = "0"
        const val DEFAULT_SIZE_PARAM = "20"
        const val MIN_PAGE = 0
        const val MIN_SIZE = 1
        const val MAX_SIZE = 100
    }
}
