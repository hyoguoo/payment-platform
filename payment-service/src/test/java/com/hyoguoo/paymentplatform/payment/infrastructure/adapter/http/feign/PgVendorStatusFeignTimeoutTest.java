package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FeignClientProperties;
import org.springframework.cloud.openfeign.FeignClientProperties.FeignClientConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * pgVendorStatus 전용 Feign 설정 계약.
 *
 * <p>이 클라이언트는 {@code pg-service}(관리자 조회용 {@link PgVendorStatusFeignClient} 의 이웃인
 * {@code PgFeignClient})와 Eureka 서비스 식별자(name)를 공유하지만 {@code contextId} 로 설정
 * 네임스페이스를 분리한다. 읽기 제한이 pg-service 가 벤더를 부르는 읽기 제한(10초, pg-service
 * application.yml {@code http.read-timeout-millis} 기본값)보다 짧으면, 정상 벤더 응답에도 먼저
 * 끊겨 상시 확인 불가로 떨어진다 — 확인 불가는 격리 종결을 허용하는 분기라 이 설정 오류가 안전장치를
 * 조용히 무력화한다. 로그만 봐서는 정상처럼 보이므로 이 관계를 테스트로 고정한다.
 */
@SpringBootTest(
        classes = PgVendorStatusFeignTimeoutTest.TimeoutTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("pgVendorStatus Feign 설정 계약 — 시간 제한 분리 + 벤더 호출 제한보다 긴 읽기 제한")
class PgVendorStatusFeignTimeoutTest {

    /**
     * pg-service application.yml {@code http.read-timeout-millis} 기본값(PG_HTTP_READ_TIMEOUT_MS)
     * — pg-service 가 벤더 confirm/getStatus 응답을 기다리는 상한.
     */
    private static final int PG_VENDOR_CALL_READ_TIMEOUT_MS = 10_000;

    @Autowired
    private FeignClientProperties feignClientProperties;

    @Test
    @DisplayName("pgVendorStatus 설정이 pg-service(관리자 조회) 설정과 분리 적용된다")
    void pgVendorStatusConfig_IsSeparateFromAdminQueryConfig() {
        FeignClientConfiguration vendorStatusConfig = feignClientProperties.getConfig().get("pgVendorStatus");
        FeignClientConfiguration adminQueryConfig = feignClientProperties.getConfig().get("pg-service");

        assertThat(vendorStatusConfig).isNotNull();
        assertThat(adminQueryConfig).isNotNull();
        assertThat(vendorStatusConfig.getReadTimeout()).isNotEqualTo(adminQueryConfig.getReadTimeout());
    }

    @Test
    @DisplayName("pgVendorStatus 읽기 제한이 pg 의 벤더 호출 읽기 제한(10초)보다 크다")
    void pgVendorStatusConfig_ReadTimeout_IsGreaterThanPgVendorCallTimeout() {
        FeignClientConfiguration vendorStatusConfig = feignClientProperties.getConfig().get("pgVendorStatus");

        assertThat(vendorStatusConfig.getReadTimeout()).isGreaterThan(PG_VENDOR_CALL_READ_TIMEOUT_MS);
    }

    @Test
    @DisplayName("클라이언트 선언에 정적 url 이 없다 — 있으면 인스턴스 목록 해석과 부하 분산을 우회한다")
    void feignClient_HasNoStaticUrl() {
        FeignClient annotation = PgVendorStatusFeignClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation.url()).isEmpty();
    }

    @Test
    @DisplayName("클라이언트의 name 은 pg-service 다 — Eureka 서비스 식별자이므로 다른 값을 주면 조회가 깨진다")
    void feignClient_NameIsPgService() {
        FeignClient annotation = PgVendorStatusFeignClient.class.getAnnotation(FeignClient.class);

        assertThat(annotation.name()).isEqualTo("pg-service");
    }

    @EnableConfigurationProperties(FeignClientProperties.class)
    @Configuration
    static class TimeoutTestConfig {
    }
}
