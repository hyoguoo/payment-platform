package com.hyoguoo.paymentplatform.user.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * user-service 표준 시계 빈 등록.
 *
 * <p>{@link Clock#systemUTC()}를 빈으로 등록해 인프라 계층이 주입 받아 사용하도록 한다.
 * 도메인은 Clock 을 주입받지 않고 Instant 인자만 받는다.
 * 테스트에서는 {@code @Primary} {@link Clock#fixed(java.time.Instant, java.time.ZoneId)} 를
 * 오버라이드해 결정적 시각을 주입한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
