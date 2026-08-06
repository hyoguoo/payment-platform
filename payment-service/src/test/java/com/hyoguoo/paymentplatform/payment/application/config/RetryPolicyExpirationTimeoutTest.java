package com.hyoguoo.paymentplatform.payment.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 재시도 최대 지연 — 결제 만료 시한 설정 계약.
 *
 * <p>소진 종결을 도입하지 않아 발행 재시도가 무한히 이어질 수 있다. 지수 백오프의 최대 지연이
 * 결제 만료 시한(READY 대기, {@code payment.expiration.ready-timeout-minutes} 기본 30분)에
 * 근접하거나 넘으면, 이미 만료된 결제에 뒤늦은 확정 명령이 나갈 수 있다. 두 값의 관계를 설정
 * 계약으로 고정해, 둘 중 하나만 바뀌어 관계가 뒤집히는 것을 막는다.
 */
@SpringBootTest(
        classes = RetryPolicyExpirationTimeoutTest.TimeoutTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("재시도 최대 지연 — 결제 만료 시한 설정 계약")
class RetryPolicyExpirationTimeoutTest {

    @Autowired
    private RetryPolicyProperties retryPolicyProperties;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("최대 지연이 결제 만료 시한보다 짧다")
    void maxDelay_IsShorterThanPaymentExpirationTimeout() {
        Duration maxDelay = Duration.ofMillis(retryPolicyProperties.getMaxDelayMs());
        long readyTimeoutMinutes = environment.getRequiredProperty(
                "payment.expiration.ready-timeout-minutes", Long.class);
        Duration expirationTimeout = Duration.ofMinutes(readyTimeoutMinutes);

        assertThat(maxDelay).isLessThan(expirationTimeout);
    }

    @EnableConfigurationProperties(RetryPolicyProperties.class)
    @Configuration
    static class TimeoutTestConfig {
    }
}
