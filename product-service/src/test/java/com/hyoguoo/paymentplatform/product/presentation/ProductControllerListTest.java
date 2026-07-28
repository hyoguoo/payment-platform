package com.hyoguoo.paymentplatform.product.presentation;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.product.application.dto.ProductPage;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductQueryService;
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /api/v1/products (목록 조회) 슬라이스 테스트.
 * 응답 형태는 payment-service 측 어댑터(Task 11)가 의존하므로 필드명·구조를 고정한다.
 */
@WebMvcTest(ProductController.class)
@DisplayName("ProductController 목록 조회 테스트")
class ProductControllerListTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private StockCommandService stockCommandService;

    @Test
    @DisplayName("목록조회_응답_형태_고정: 내용·페이지·크기·전체건수·전체페이지 필드를 반환한다")
    void 목록조회_응답_형태_고정() throws Exception {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("상품-1")
                .price(BigDecimal.valueOf(1000))
                .description("설명")
                .stock(42)
                .sellerId(1L)
                .allArgsBuild();
        ProductPage productPage = new ProductPage(List.of(product), 0, 20, 1L);
        given(productQueryService.getPage(0, 20)).willReturn(productPage);

        // when / then
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("상품-1"))
                .andExpect(jsonPath("$.content[0].stock").value(42))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("목록조회_페이지_파라미터_기본값_적용: 쿼리 파라미터가 없으면 0페이지·20건으로 조회한다")
    void 목록조회_페이지_파라미터_기본값_적용() throws Exception {
        // given
        given(productQueryService.getPage(anyInt(), anyInt()))
                .willReturn(new ProductPage(List.of(), 0, 20, 0L));

        // when
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());

        // then
        verify(productQueryService).getPage(0, 20);
    }

    @Test
    @DisplayName("목록조회_크기_상한_적용: 상한을 넘는 크기를 요청해도 100건으로 잘려 조회된다")
    void 목록조회_크기_상한_적용() throws Exception {
        // given
        given(productQueryService.getPage(anyInt(), anyInt()))
                .willReturn(new ProductPage(List.of(), 0, 100, 0L));

        // when
        mockMvc.perform(get("/api/v1/products").param("page", "0").param("size", "1000"))
                .andExpect(status().isOk());

        // then
        verify(productQueryService).getPage(0, 100);
    }
}
