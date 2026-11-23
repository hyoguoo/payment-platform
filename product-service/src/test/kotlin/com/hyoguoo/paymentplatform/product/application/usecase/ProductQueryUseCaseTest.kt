package com.hyoguoo.paymentplatform.product.application.usecase

import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository
import com.hyoguoo.paymentplatform.product.domain.Product
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal

@DisplayName("ProductQueryUseCase 테스트")
class ProductQueryUseCaseTest {
    private lateinit var productRepository: ProductRepository
    private lateinit var sut: ProductQueryUseCase

    @BeforeEach
    fun setUp() {
        productRepository = mockk()
        sut = ProductQueryUseCase(productRepository)
    }

    @Test
    @DisplayName("getById: 상품이 존재하면 Product 도메인 객체를 반환한다")
    fun getById_whenProductExists_returnsProduct() {
        // given
        val productId = 1L
        val product = Product(productId, "테스트 상품", BigDecimal.valueOf(10000), "상품 설명", 50, 1L)
        every { productRepository.findById(productId) } returns product

        // when
        val result = sut.getById(productId)

        // then
        assertThat(result.id).isEqualTo(productId)
        assertThat(result.name).isEqualTo("테스트 상품")
        assertThat(result.stock).isEqualTo(50)
    }

    @Test
    @DisplayName("getById: 상품이 존재하지 않으면 ProductNotFoundException을 던진다")
    fun getById_whenProductNotFound_throwsProductNotFoundException() {
        // given
        val productId = 999L
        every { productRepository.findById(productId) } returns null

        // when & then
        assertThatThrownBy { sut.getById(productId) }
            .isInstanceOf(ProductNotFoundException::class.java)
    }
}
