package com.hyoguoo.paymentplatform.core.config;

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
        log.info("=================================================");
        log.info("Scheduler is ENABLED - Background tasks will run");
        log.info("=================================================");
    }
}
