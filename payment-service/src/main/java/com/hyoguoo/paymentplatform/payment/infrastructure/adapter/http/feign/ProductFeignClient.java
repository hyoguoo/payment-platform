package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * product-service 호출 전용 선언적 Feign client.
 *
 * <p>Eureka logical name "product-service" 로 인스턴스 list resolve + LB.
 * 4xx/5xx → 도메인 예외 매핑은 ProductFeignConfig 의 ErrorDecoder 가 담당.
 *
 * <p>현재 ProductHttpAdapter 가 호출하는 endpoint 와 정확히 동일한 시그니처.
 * GET /api/v1/products/{id} — 단건 조회.
 */
@FeignClient(name = "product-service", configuration = ProductFeignConfig.class)
public interface ProductFeignClient {

    @GetMapping("/api/v1/products/{id}")
    ProductResponse getProductById(@PathVariable("id") Long id);
}
