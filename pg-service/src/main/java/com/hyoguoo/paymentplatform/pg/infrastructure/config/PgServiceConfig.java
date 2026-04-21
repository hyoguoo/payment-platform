package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * pg-service 공통 설정.
 *
 * <ul>
 *   <li>Clock Bean: PgOutboxPollingWorker, PgOutboxMetrics 등 시간 기반 컴포넌트 공통 주입.
 *   <li>EnableScheduling: PgOutboxPollingWorker(@Scheduled) + PgOutboxMetrics(@Scheduled) 활성화.
 * </ul>
 */
@Configuration
@EnableScheduling
public class PgServiceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
