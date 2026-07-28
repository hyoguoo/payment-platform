package com.hyoguoo.paymentplatform.payment.presentation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogEntryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogLookupResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.presentation.port.StockCatalogViewService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code StockViewController} 테스트.
 *
 * <p>조회 실패 흡수와 조회 불가 폴백 판단은 {@link StockCatalogViewService}(입력 포트)
 * 구현으로 옮겨갔다 — 이 테스트는 그 결과({@link ProductCatalogLookupResult})를 컨트롤러가
 * 모델에 올바르게 담는지만 검증한다.
 */
@WebMvcTest(StockViewController.class)
class StockViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockCatalogViewService stockCatalogViewService;

    @Test
    @DisplayName("재고 화면은 조회 성공 시 확정 수량 목록을 모델에 담는다.")
    void 재고화면_확정수량_목록_모델에_담김() throws Exception {
        // given
        given(stockCatalogViewService.getPage(0, 20)).willReturn(ProductCatalogLookupResult.available(pageInfo()));

        // when / then
        mockMvc.perform(get("/admin/stocks"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("products"))
                .andExpect(model().attribute("unavailable", false));
    }

    @Test
    @DisplayName("상품 서비스 조회가 조회 불가여도 화면은 500 대신 조회 불가 안내를 담아 렌더된다.")
    void 재고화면_조회_실패시_안내_표시() throws Exception {
        // given
        given(stockCatalogViewService.getPage(0, 20)).willReturn(ProductCatalogLookupResult.unavailable());

        // when / then
        mockMvc.perform(get("/admin/stocks"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("unavailable", true))
                .andExpect(model().attributeDoesNotExist("products"));
    }

    @Test
    @DisplayName("재고 화면은 요청받은 페이지 파라미터를 그대로 조회 포트에 전달한다.")
    void 재고화면_페이지_파라미터_전달() throws Exception {
        // given
        given(stockCatalogViewService.getPage(2, 10)).willReturn(ProductCatalogLookupResult.available(pageInfo()));

        // when / then
        mockMvc.perform(get("/admin/stocks").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());

        then(stockCatalogViewService).should().getPage(2, 10);
    }

    private ProductCatalogPageInfo pageInfo() {
        ProductCatalogEntryInfo entry = ProductCatalogEntryInfo.builder()
                .id(1L)
                .name("test product")
                .price(BigDecimal.valueOf(10_000))
                .confirmedStock(5)
                .sellerId(100L)
                .build();

        return ProductCatalogPageInfo.builder()
                .content(List.of(entry))
                .page(0)
                .size(20)
                .totalElements(1)
                .totalPages(1)
                .build();
    }
}
