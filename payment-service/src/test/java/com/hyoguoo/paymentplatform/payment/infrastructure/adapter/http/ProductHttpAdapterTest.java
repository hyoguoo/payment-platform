package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductHttpAdapter 테스트")
class ProductHttpAdapterTest {

    @Mock
    private HttpOperator httpOperator;

    @InjectMocks
    private ProductHttpAdapter productHttpAdapter;

    @Test
    @DisplayName("getProduct_ShouldCallProductServiceAndReturnDomain: HTTP 응답이 도메인 DTO로 변환된다")
    void getProduct_ShouldCallProductServiceAndReturnDomain() {
        // given
        ProductResponse response = new ProductResponse(
                1L, "상품A", new BigDecimal("10000"), 50, 100L
        );
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductResponse.class)))
                .willReturn(response);

        // when
        ProductInfo result = productHttpAdapter.getProductInfoById(1L);

        // then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("상품A");
        assertThat(result.getPrice()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(result.getStock()).isEqualTo(50);
        assertThat(result.getSellerId()).isEqualTo(100L);
    }

}
