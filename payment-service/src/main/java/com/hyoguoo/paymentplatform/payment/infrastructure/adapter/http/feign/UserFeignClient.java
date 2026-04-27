package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * user-service 호출 전용 선언적 Feign client.
 *
 * <p>Eureka logical name "user-service" 로 인스턴스 list resolve + LB.
 * 4xx/5xx → 도메인 예외 매핑은 UserFeignConfig 의 ErrorDecoder (B3) 가 담당.
 *
 * <p>현재 UserHttpAdapter 가 호출하는 endpoint 와 정확히 동일한 시그니처.
 * GET /api/v1/users/{id} — 단건 조회.
 */
@FeignClient(name = "user-service", configuration = UserFeignConfig.class)
public interface UserFeignClient {

    @GetMapping("/api/v1/users/{id}")
    UserResponse getUserById(@PathVariable("id") Long id);
}
