package com.hyoguoo.paymentplatform.product.presentation

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage
import com.hyoguoo.paymentplatform.product.domain.Product
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

/**
 * GET /api/v1/products (목록 조회) 슬라이스 테스트.
 * 응답 형태는 payment-service 측 어댑터(Task 11)가 의존하므로 필드명·구조를 고정한다.
 */
@WebMvcTest(ProductController::class)
@DisplayName("ProductController 목록 조회 테스트")
class ProductControllerListTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var productQueryService: ProductQueryService

    // 컨트롤러 생성에 필요한 빈이라 테스트에서 직접 쓰지 않아도 둔다
    @MockkBean
    lateinit var stockCommandService: StockCommandService

    @Test
    @DisplayName("목록조회_응답_형태_고정: 내용·페이지·크기·전체건수·전체페이지 필드를 반환한다")
    fun `목록조회_응답_형태_고정`() {
        // given
        val product = Product(1L, "상품-1", BigDecimal.valueOf(1000), "설명", 42, 1L)
        val productPage = ProductPage(listOf(product), 0, 20, 1L)
        every { productQueryService.getPage(0, 20) } returns productPage

        // when / then
        mockMvc
            .perform(get("/api/v1/products"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(1))
            .andExpect(jsonPath("$.content[0].name").value("상품-1"))
            .andExpect(jsonPath("$.content[0].stock").value(42))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
    }

    @Test
    @DisplayName("목록조회_페이지_파라미터_기본값_적용: 쿼리 파라미터가 없으면 0페이지·20건으로 조회한다")
    fun `목록조회_페이지_파라미터_기본값_적용`() {
        // given
        every { productQueryService.getPage(any(), any()) } returns ProductPage(listOf(), 0, 20, 0L)

        // when
        mockMvc
            .perform(get("/api/v1/products"))
            .andExpect(status().isOk)

        // then
        verify(exactly = 1) { productQueryService.getPage(0, 20) }
    }

    @Test
    @DisplayName("목록조회_크기_상한_적용: 상한을 넘는 크기를 요청해도 100건으로 잘려 조회된다")
    fun `목록조회_크기_상한_적용`() {
        // given
        every { productQueryService.getPage(any(), any()) } returns ProductPage(listOf(), 0, 100, 0L)

        // when
        mockMvc
            .perform(get("/api/v1/products").param("page", "0").param("size", "1000"))
            .andExpect(status().isOk)

        // then
        verify(exactly = 1) { productQueryService.getPage(0, 100) }
    }
}
