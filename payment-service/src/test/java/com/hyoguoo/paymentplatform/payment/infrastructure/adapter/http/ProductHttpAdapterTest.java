package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
        ProductHttpAdapter.ProductResponse response = new ProductHttpAdapter.ProductResponse(
                1L, "상품A", new BigDecimal("10000"), 50, 100L
        );
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductHttpAdapter.ProductResponse.class)))
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

    @Test
    @DisplayName("decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException: HTTP 503 응답 시 RetryableException 발생")
    void decreaseStock_WhenServiceUnavailable_ShouldThrowRetryableException() {
        // given
        List<OrderedProductStockCommand> commands = List.of(
                OrderedProductStockCommand.builder().productId(1L).stock(2).build()
        );
        given(httpOperator.requestPost(anyString(), any(Map.class), any(), any()))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        "Service Unavailable",
                        null,
                        null,
                        StandardCharsets.UTF_8
                ));

        // when & then
        assertThatThrownBy(() -> productHttpAdapter.decreaseStockForOrders(commands))
                .isInstanceOf(ProductServiceRetryableException.class);
    }
}
