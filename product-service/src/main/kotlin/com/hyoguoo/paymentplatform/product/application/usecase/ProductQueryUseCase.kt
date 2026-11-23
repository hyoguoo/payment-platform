package com.hyoguoo.paymentplatform.product.application.usecase

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage
import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository
import com.hyoguoo.paymentplatform.product.domain.Product
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 상품 조회 유스케이스.
 * ProductRepository.findById → 없으면 ProductNotFoundException, 있으면 Product 도메인 반환.
 */
@Service
class ProductQueryUseCase(
    private val productRepository: ProductRepository,
) : ProductQueryService {
    @Transactional(readOnly = true)
    override fun getById(id: Long): Product =
        productRepository.findById(id) ?: throw ProductNotFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND)

    @Transactional(readOnly = true)
    override fun getPage(
        page: Int,
        size: Int,
    ): ProductPage = productRepository.findPage(page, size)
}
