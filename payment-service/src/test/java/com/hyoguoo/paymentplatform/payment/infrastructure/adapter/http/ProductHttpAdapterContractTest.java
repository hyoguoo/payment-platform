package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * ProductHttpAdapter 404/503/429/500 응답 분기 계약 테스트.
 *
 * <p>T3.5-10 산출물. f3e0334f 수습분(서브도메인 404/5xx 매핑) 회귀 방지:
 * <ul>
 *   <li>404 → ProductNotFoundException (PaymentErrorCode.PRODUCT_NOT_FOUND)</li>
 *   <li>503 → ProductServiceRetryableException (PRODUCT_SERVICE_UNAVAILABLE)</li>
 *   <li>429 → ProductServiceRetryableException (PRODUCT_SERVICE_UNAVAILABLE)</li>
 *   <li>500 → IllegalStateException</li>
 * </ul>
 *
 * <p>Strangler Vine 원칙: 기존 ProductHttpAdapterTest 의 happy path 2 케이스는 유지.
 * 본 계약 테스트는 실패·격리 경로 분기 커버리지만 담당한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductHttpAdapter 계약 — 404/503/429/500 응답 분기")
class ProductHttpAdapterContractTest {

    @Mock
    private HttpOperator httpOperator;

    @InjectMocks
    private ProductHttpAdapter adapter;

    @Test
    @DisplayName("404 응답 → ProductNotFoundException(PRODUCT_NOT_FOUND)")
    void getProduct_NotFound_ShouldThrowProductNotFoundException() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.NOT_FOUND.value(), "Not Found", null, null, null));

        assertThatThrownBy(() -> adapter.getProductInfoById(999L))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("503 응답 → ProductServiceRetryableException(PRODUCT_SERVICE_UNAVAILABLE)")
    void getProduct_ServiceUnavailable_ShouldThrowRetryable() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable", null, null, null));

        assertThatThrownBy(() -> adapter.getProductInfoById(1L))
                .isInstanceOf(ProductServiceRetryableException.class);
    }

    @Test
    @DisplayName("429 응답 → ProductServiceRetryableException(Too Many Requests)")
    void getProduct_TooManyRequests_ShouldThrowRetryable() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", null, null, null));

        assertThatThrownBy(() -> adapter.getProductInfoById(1L))
                .isInstanceOf(ProductServiceRetryableException.class);
    }

    @Test
    @DisplayName("500 응답 → IllegalStateException (미분류 서버 에러)")
    void getProduct_InternalServerError_ShouldThrowIllegalState() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(ProductResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", null, null, null));

        assertThatThrownBy(() -> adapter.getProductInfoById(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=500");
    }
}
