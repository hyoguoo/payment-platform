package com.hyoguoo.paymentplatform.payment.core.config;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "scheduler.enabled",
        havingValue = "true"
)
public class SchedulerConfig {

    @PostConstruct
    public void init() {
        LogFmt.info(log, LogDomain.GLOBAL, EventType.SCHEDULER_ENABLED,
                () -> "background tasks enabled");
    }
}
