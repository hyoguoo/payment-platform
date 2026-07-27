package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductPageResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * product-service 호출 전용 선언적 Feign client.
 *
 * <p>Eureka logical name "product-service" 로 인스턴스 list resolve + LB.
 * 4xx/5xx → 도메인 예외 매핑은 ProductFeignConfig 의 ErrorDecoder 가 담당.
 *
 * <p>ProductHttpAdapter(승인 경로) 와 ProductCatalogHttpAdapter(관리자 재고 화면 조회)
 * 양쪽이 이 client 를 공유한다 — 중복 client 를 만들지 않는다.
 *
 * <ul>
 *   <li>GET /api/v1/products/{id} — 단건 조회 (ProductHttpAdapter)</li>
 *   <li>GET /api/v1/products?page=&size= — 목록 페이징 조회 (ProductCatalogHttpAdapter)</li>
 * </ul>
 */
@FeignClient(name = "product-service", configuration = ProductFeignConfig.class)
public interface ProductFeignClient {

    @GetMapping("/api/v1/products/{id}")
    ProductResponse getProductById(@PathVariable("id") Long id);

    @GetMapping("/api/v1/products")
    ProductPageResponse getProducts(@RequestParam("page") int page, @RequestParam("size") int size);
}
