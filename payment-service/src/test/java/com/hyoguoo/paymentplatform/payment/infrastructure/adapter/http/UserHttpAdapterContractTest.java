package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.UserResponse;
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
 * UserHttpAdapter 404/503/429/500 응답 분기 계약 테스트.
 *
 * <p>T3.5-10 산출물. f3e0334f 수습분 회귀 방지:
 * <ul>
 *   <li>404 → UserNotFoundException (PaymentErrorCode.USER_NOT_FOUND)</li>
 *   <li>503 → UserServiceRetryableException (USER_SERVICE_UNAVAILABLE)</li>
 *   <li>429 → UserServiceRetryableException (USER_SERVICE_UNAVAILABLE)</li>
 *   <li>500 → IllegalStateException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserHttpAdapter 계약 — 404/503/429/500 응답 분기")
class UserHttpAdapterContractTest {

    @Mock
    private HttpOperator httpOperator;

    @InjectMocks
    private UserHttpAdapter adapter;

    @Test
    @DisplayName("404 응답 → UserNotFoundException(USER_NOT_FOUND)")
    void getUser_NotFound_ShouldThrowUserNotFoundException() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(UserResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.NOT_FOUND.value(), "Not Found", null, null, null));

        assertThatThrownBy(() -> adapter.getUserInfoById(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("503 응답 → UserServiceRetryableException(USER_SERVICE_UNAVAILABLE)")
    void getUser_ServiceUnavailable_ShouldThrowRetryable() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(UserResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable", null, null, null));

        assertThatThrownBy(() -> adapter.getUserInfoById(1L))
                .isInstanceOf(UserServiceRetryableException.class);
    }

    @Test
    @DisplayName("429 응답 → UserServiceRetryableException(Too Many Requests)")
    void getUser_TooManyRequests_ShouldThrowRetryable() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(UserResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests", null, null, null));

        assertThatThrownBy(() -> adapter.getUserInfoById(1L))
                .isInstanceOf(UserServiceRetryableException.class);
    }

    @Test
    @DisplayName("500 응답 → IllegalStateException (미분류 서버 에러)")
    void getUser_InternalServerError_ShouldThrowIllegalState() {
        given(httpOperator.requestGet(anyString(), any(Map.class), eq(UserResponse.class)))
                .willThrow(WebClientResponseException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", null, null, null));

        assertThatThrownBy(() -> adapter.getUserInfoById(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=500");
    }
}
