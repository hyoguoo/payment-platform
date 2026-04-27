package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.UserFeignClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * UserHttpAdapter FeignClient 예외 propagation 계약 테스트.
 *
 * <p>ErrorDecoder 가 throw 한 도메인 예외를 어댑터가 그대로 propagate 하는지 검증한다.
 * 4xx/5xx → 도메인 예외 매핑 자체는 UserFeignConfigTest 에서 별도 검증한다.
 *
 * <ul>
 *   <li>FeignClient 가 {@link UserNotFoundException} throw → 어댑터가 그대로 propagate</li>
 *   <li>FeignClient 가 {@link UserServiceRetryableException} throw → 어댑터가 그대로 propagate</li>
 *   <li>FeignClient 가 {@link feign.RetryableException} throw → 어댑터가 {@link UserServiceRetryableException} 으로 변환</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserHttpAdapter 계약 — FeignClient 예외 propagation")
class UserHttpAdapterContractTest {

    @Mock
    private UserFeignClient userFeignClient;

    @InjectMocks
    private UserHttpAdapter adapter;

    @Test
    @DisplayName("FeignClient 가 UserNotFoundException throw → 어댑터가 그대로 propagate")
    void getUser_WhenFeignThrowsUserNotFound_ShouldPropagate() {
        given(userFeignClient.getUserById(999L))
                .willThrow(UserNotFoundException.of(PaymentErrorCode.USER_NOT_FOUND));

        assertThatThrownBy(() -> adapter.getUserInfoById(999L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("FeignClient 가 UserServiceRetryableException throw → 어댑터가 그대로 propagate")
    void getUser_WhenFeignThrowsRetryable_ShouldPropagate() {
        given(userFeignClient.getUserById(1L))
                .willThrow(UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.getUserInfoById(1L))
                .isInstanceOf(UserServiceRetryableException.class);
    }

    @Test
    @DisplayName("FeignClient 가 feign.RetryableException(transport 오류) throw → 어댑터가 UserServiceRetryableException 으로 변환")
    void getUser_WhenFeignThrowsTransportRetryableException_ShouldConvertToUserServiceRetryable() {
        feign.RetryableException transportException = mock(feign.RetryableException.class);
        given(userFeignClient.getUserById(1L)).willThrow(transportException);

        assertThatThrownBy(() -> adapter.getUserInfoById(1L))
                .isInstanceOf(UserServiceRetryableException.class);
    }
}
