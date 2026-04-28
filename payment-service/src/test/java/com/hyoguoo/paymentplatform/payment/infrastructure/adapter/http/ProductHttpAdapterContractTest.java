package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.ProductFeignClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ProductHttpAdapter FeignClient 예외 propagation 계약 테스트.
 *
 * <p>ErrorDecoder 가 throw 한 도메인 예외를 어댑터가 그대로 propagate 하는지 검증한다.
 * 4xx/5xx → 도메인 예외 매핑 자체는 ProductFeignConfigTest 에서 별도 검증한다.
 *
 * <ul>
 *   <li>FeignClient 가 {@link ProductNotFoundException} throw → 어댑터가 그대로 propagate</li>
 *   <li>FeignClient 가 {@link ProductServiceRetryableException} throw → 어댑터가 그대로 propagate</li>
 *   <li>FeignClient 가 {@link feign.RetryableException} throw → 어댑터가 {@link ProductServiceRetryableException} 으로 변환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductHttpAdapter 계약 — FeignClient 예외 propagation")
class ProductHttpAdapterContractTest {

    @Mock
    private ProductFeignClient productFeignClient;

    @InjectMocks
    private ProductHttpAdapter adapter;

    @Test
    @DisplayName("FeignClient 가 ProductNotFoundException throw → 어댑터가 그대로 propagate")
    void getProduct_WhenFeignThrowsProductNotFound_ShouldPropagate() {
        given(productFeignClient.getProductById(999L))
                .willThrow(ProductNotFoundException.of(PaymentErrorCode.PRODUCT_NOT_FOUND));

        assertThatThrownBy(() -> adapter.getProductInfoById(999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("FeignClient 가 ProductServiceRetryableException throw → 어댑터가 그대로 propagate")
    void getProduct_WhenFeignThrowsRetryable_ShouldPropagate() {
        given(productFeignClient.getProductById(1L))
                .willThrow(ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.getProductInfoById(1L))
                .isInstanceOf(ProductServiceRetryableException.class);
    }

    @Test
    @DisplayName("FeignClient 가 feign.RetryableException(transport 오류) throw → 어댑터가 ProductServiceRetryableException 으로 변환")
    void getProduct_WhenFeignThrowsTransportRetryableException_ShouldConvertToProductServiceRetryable() {
        feign.RetryableException transportException = mock(feign.RetryableException.class);
        given(productFeignClient.getProductById(1L)).willThrow(transportException);

        assertThatThrownBy(() -> adapter.getProductInfoById(1L))
                .isInstanceOf(ProductServiceRetryableException.class);
    }
}
