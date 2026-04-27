package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.application.port.out.UserPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.UserResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.UserFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * user-service HTTP 어댑터.
 * UserPort 구현체로, {@code user.adapter.type=http} 프로파일에서 활성화된다.
 * 4xx/5xx → 도메인 예외 매핑은 {@link UserFeignClient} 에 연결된 ErrorDecoder 가 담당한다.
 * transport-level 예외(RetryableException) 는 이 클래스에서 UserServiceRetryableException 으로 매핑한다.
 * Resilience4j {@code @CircuitBreaker} 는 Phase 4 에서 이 클래스 내부 메서드에 설치한다 — port 인터페이스는 회복성 어노테이션으로부터 격리된 채로 유지한다.
 * NOTE(T4-D): fallbackFactory 로 마이그레이션 시 이 try/catch 블록 제거 예정.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "user.adapter.type", havingValue = "http")
public class UserHttpAdapter implements UserPort {

    private final UserFeignClient userFeignClient;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        try {
            UserResponse response = userFeignClient.getUserById(userId);
            return toUserInfo(response);
        } catch (feign.RetryableException e) {
            LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_RETRYABLE,
                    () -> "transport=" + e.getMessage());
            throw UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE);
        }
    }

    private static UserInfo toUserInfo(UserResponse response) {
        return UserInfo.builder()
                .id(response.id())
                .build();
    }
}
