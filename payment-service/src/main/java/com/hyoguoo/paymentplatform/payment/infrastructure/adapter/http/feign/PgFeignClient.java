package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgAttemptHistoryResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * pg-service 관리자 조회 전용 선언적 Feign client.
 * payment-service 가 pg-service 를 HTTP 로 부르는 최초의 경로 — 지금까지 두 서비스는 Kafka 로만 대화했다.
 *
 * <p>Eureka logical name "pg-service" 로 인스턴스 list resolve + LB.
 * 4xx/5xx → 도메인 예외 매핑은 PgFeignConfig 의 ErrorDecoder 가 담당.
 * 연결/읽기 타임아웃은 관리자 조회 전용으로 기본값보다 짧게 별도 설정한다(application.yml
 * spring.cloud.openfeign.client.config.pg-service).
 *
 * <p>pg-service PgAttemptHistoryController(Task 5)의 엔드포인트와 정확히 동일한 시그니처.
 * GET /api/v1/confirmations/{orderId}/attempts — 확정 시도 이력 조회.
 */
@FeignClient(name = "pg-service", configuration = PgFeignConfig.class)
public interface PgFeignClient {

    @GetMapping("/api/v1/confirmations/{orderId}/attempts")
    PgAttemptHistoryResponse getAttemptHistory(@PathVariable("orderId") String orderId);
}
