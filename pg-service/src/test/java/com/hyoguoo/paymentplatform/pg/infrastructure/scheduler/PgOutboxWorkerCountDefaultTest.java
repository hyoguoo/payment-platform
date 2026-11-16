package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * pg outbox 즉시 발행 워커 수 기본값 검증.
 *
 * <p>워커가 작업을 직접 처리하므로(#166) 워커 수가 동시 발행 상한이다. 워커 1개는 capacity 160/s 측정에서
 * 약 145/s에서 발행이 막혔다(EXPERIMENTS 2026-11-16). 환경 변수 없이 띄웠을 때의 값을 application.yml 에서 읽어 확인한다.
 */
@DisplayName("pg outbox 워커 수 기본값")
class PgOutboxWorkerCountDefaultTest {

    private static final String WORKER_COUNT_KEY = "pg.outbox.channel.worker-count";

    @Test
    @DisplayName("환경 변수를 주지 않으면 application.yml 의 outbox 워커 수는 8이다")
    void outboxWorkerCount_defaultsToEight() throws IOException {
        AbstractEnvironment environment = new AbstractEnvironment() {
        };
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        documents.stream()
                .filter(document -> document.getProperty("spring.config.activate.on-profile") == null)
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty(WORKER_COUNT_KEY, Integer.class)).isEqualTo(8);
    }
}
