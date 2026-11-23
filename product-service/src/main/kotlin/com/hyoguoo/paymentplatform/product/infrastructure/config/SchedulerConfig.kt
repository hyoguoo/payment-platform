package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄링 활성화 설정.
 *
 * `scheduler.enabled=true` 일 때만 활성화되어 `@Scheduled` 컴포넌트가 동작한다.
 * 단위 테스트에서는 이 빈이 로드되지 않으므로 스케줄러가 자동 실행되지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["scheduler.enabled"], havingValue = "true")
class SchedulerConfig {
    @PostConstruct
    fun init() {
        LogFmt.info(log, LogDomain.GLOBAL, EventType.SCHEDULER_ENABLED) { "background tasks enabled" }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SchedulerConfig::class.java)
    }
}
