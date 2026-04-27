package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.application.port.out.UserPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
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
 * Resilience4j {@code @CircuitBreaker} 는 Phase 4 에서 이 클래스 내부 메서드에 설치한다 — port 인터페이스는 회복성 어노테이션으로부터 격리된 채로 유지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "user.adapter.type", havingValue = "http")
public class UserHttpAdapter implements UserPort {

    private final UserFeignClient userFeignClient;

    @Override
    public UserInfo getUserInfoById(Long userId) {
        UserResponse response = userFeignClient.getUserById(userId);
        return toUserInfo(response);
    }

    private static UserInfo toUserInfo(UserResponse response) {
        return UserInfo.builder()
                .id(response.id())
                .build();
    }
}
