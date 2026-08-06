package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgVendorStatusResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * pg-service 벤더 상태 조회 전용 선언적 Feign client — 격리 종결 판단 직전 벤더에 1회 묻는다.
 *
 * <p>{@code name} 은 반드시 "pg-service" 로 둔다 — Eureka 인스턴스 목록 resolve + 부하 분산에 쓰이는
 * 서비스 식별자다. 다른 값을 주면 조회가 깨지고, 그걸 정적 {@code url} 로 우회하면 인스턴스 해석과
 * 부하 분산을 건너뛰어 특정 인스턴스가 죽었을 때 조회가 통째로 실패한다 — 확인 불가는 격리 종결을
 * 허용하는 분기라 이 라우팅 오류가 안전장치를 조용히 무력화한다.
 *
 * <p>기존 {@link PgFeignClient}(관리자 시도 이력 조회)와 {@code name} 이 같다(둘 다 pg-service 를
 * 가리킨다). {@code contextId} 로 설정 네임스페이스만 나눈다 — 이 저장소에 {@code contextId} 선례는
 * 없지만, 두 클라이언트가 같은 Eureka 서비스를 서로 다른 시간 제한으로 불러야 해서(이쪽은 pg 가
 * 벤더를 부르는 시간보다 길게, 관리자 이력 조회는 짧게) {@code name} 단위로 걸리는
 * {@code spring.cloud.openfeign.client.config} 설정을 나눌 방법이 이것뿐이다. 설정 키도
 * {@code contextId} 값(pgVendorStatus)을 따른다.
 *
 * <p>pg-service PgAttemptHistoryController 의 GET /api/v1/confirmations/{orderId}/vendor-status
 * 와 정확히 동일한 시그니처.
 */
@FeignClient(name = "pg-service", contextId = "pgVendorStatus", configuration = PgVendorStatusFeignConfig.class)
public interface PgVendorStatusFeignClient {

    @GetMapping("/api/v1/confirmations/{orderId}/vendor-status")
    PgVendorStatusResponse getVendorStatus(@PathVariable("orderId") String orderId);
}
