package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductPageResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.ProductFeignClient;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ProductCatalogHttpAdapter 계약 테스트 — ProductHttpAdapterContractTest / PgAttemptHistoryHttpAdapterContractTest 패턴.
 *
 * <p>4xx/5xx → 도메인 예외 매핑 자체는 ProductFeignConfigTest 에서 이미 검증됐다(ProductFeignClient 공유).
 * 여기서는 FeignClient 가 이미 도메인 예외를 던진 경우 어댑터가 그대로 propagate 하는지,
 * transport-level 예외를 변환하는지, 정상 응답의 확정 수량이 누락 없이 도메인 DTO 로 옮겨지는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductCatalogHttpAdapter 계약 — FeignClient 예외 propagation / 응답 변환")
class ProductCatalogHttpAdapterContractTest {

    @Mock
    private ProductFeignClient productFeignClient;

    @InjectMocks
    private ProductCatalogHttpAdapter adapter;

    @Test
    @DisplayName("FeignClient 가 도메인 예외(ProductNotFoundException) throw → 어댑터가 그대로 propagate")
    void getPage_WhenFeignThrowsDomainException_ShouldPropagate() {
        given(productFeignClient.getProducts(0, 20))
                .willThrow(ProductNotFoundException.of(PaymentErrorCode.PRODUCT_NOT_FOUND));

        assertThatThrownBy(() -> adapter.getPage(0, 20))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("FeignClient 가 feign.RetryableException(transport 오류) throw → 어댑터가 ProductServiceRetryableException 으로 변환")
    void getPage_WhenFeignThrowsTransportRetryableException_ShouldConvertToRetryable() {
        feign.RetryableException transportException = mock(feign.RetryableException.class);
        given(productFeignClient.getProducts(0, 20)).willThrow(transportException);

        assertThatThrownBy(() -> adapter.getPage(0, 20))
                .isInstanceOf(ProductServiceRetryableException.class);
    }

    @Test
    @DisplayName("정상 응답 → 도메인 DTO 변환 (확정 수량 누락 없이)")
    void getPage_WhenSuccess_ShouldConvertToDomainDtoWithConfirmedStock() {
        ProductResponse entryResponse = new ProductResponse(1L, "상품A", BigDecimal.valueOf(1000), 42, 10L);
        ProductPageResponse response = new ProductPageResponse(List.of(entryResponse), 0, 20, 1L, 1);
        given(productFeignClient.getProducts(0, 20)).willReturn(response);

        ProductCatalogPageInfo result = adapter.getPage(0, 20);

        assertThat(result.getPage()).isEqualTo(0);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(1L);
        assertThat(result.getTotalPages()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).getName()).isEqualTo("상품A");
        assertThat(result.getContent().get(0).getPrice()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(result.getContent().get(0).getConfirmedStock()).isEqualTo(42);
        assertThat(result.getContent().get(0).getSellerId()).isEqualTo(10L);
    }
}
