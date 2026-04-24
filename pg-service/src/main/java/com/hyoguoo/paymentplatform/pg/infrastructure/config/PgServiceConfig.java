package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
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
 *   <li>T-E1: {@link PgSlf4jMdcThreadLocalAccessor} 를 {@code ContextRegistry} 에 등록
 *       — {@code ContextExecutorService.wrap()} 경유 VT 실행 시 MDC 승계 보장.
 * </ul>
 */
@Configuration
@EnableScheduling
public class PgServiceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * T-E1: 애플리케이션 기동 직후 Slf4j MDC ThreadLocalAccessor 를 ContextRegistry 에 등록.
     * 이를 통해 ContextExecutorService.wrap() 이 VT submit 시 MDC 스냅샷을 캡처·복원한다.
     */
    @PostConstruct
    public void registerMdcAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new PgSlf4jMdcThreadLocalAccessor());
    }
}
